use std::borrow::Cow;
use std::collections::HashMap;
use std::net::SocketAddr;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use futures::StreamExt;
use tokio::io::AsyncReadExt;
use tokio::sync::mpsc;
use tsclientlib::data::exts::*;
use tsclientlib::data::{self, ChannelOptions};
use tsclientlib::events::{Event, PropertyId, PropertyValue};
use tsclientlib::messages::c2s;
use tsclientlib::prelude::*;
use tsclientlib::{
    ChannelId, ChannelType, ClientId, ClientType, Codec, CommandError, Connection,
    DisconnectOptions, FiletransferHandle, InMessage, MaxClients, MessageHandle, MessageTarget,
    Reason, StreamItem, TsError,
};
use tsproto_packets::packets::{AudioData, Direction, Flags, OutCommand, OutPacket, PacketType};

use crate::audio_engine::RealtimeAudioEngine;
use crate::events::{
    is_en, ChannelNodeDto, ChatMessageDto, CoreEventEnvelope, IdentityInfoDto, OnlineClientDto,
    PokeAlertDto, ServerSnapshotDto,
};
use crate::identity::parse_identity_any;
use crate::resolver::{invalidate_dns_cache, resolve_teamspeak_server};
use crate::streaming::{
    build_join_stream_request_cmd, build_remove_client_from_stream_cmd,
    build_request_stream_info_cmd, build_respond_join_stream_cmd, build_setup_stream_cmd,
    build_stop_stream_cmd, build_stream_signaling_cmd, Ts6StreamTracker, STREAM_TYPE_SCREEN,
};

pub enum SessionCommand {
    Connect {
        session_id: i64,
        address: String,
        port: u16,
        nickname: String,
        identity_ini: String,
        server_password: String,
        default_channel: String,
        channel_password: String,
        dns_servers_csv: String,
    },
    Disconnect {
        reason: String,
    },
    DisconnectSync {
        reason: String,
        timeout_ms: u64,
        done_tx: std::sync::mpsc::SyncSender<()>,
    },
    SwitchChannel {
        channel_id: u64,
        password: String,
    },
    SendMessage {
        target_mode: i32,
        target_id: u64,
        message: String,
    },
    SetSelfMute {
        input_muted: bool,
        output_muted: bool,
        away: bool,
        away_message: String,
    },
    SendAudio(OutPacket),
    SendPoke {
        client_id: u16,
        message: String,
    },
    UsePrivilegeKey {
        token: String,
    },
    RequestTalkPower {
        request: bool,
        message: String,
    },
    CreateChannel {
        name: String,
        parent_id: u64,
        password: String,
        topic: String,
        description: String,
        max_clients: i32,
        channel_type: i32, // 0 = Temporary, 1 = SemiPermanent, 2 = Permanent
        is_default: bool,
        opus_music: bool,
        quality: u8,
        needed_talk_power: i32,
        auto_join: bool,
    },
    EditChannel {
        channel_id: u64,
        parent_id: i64,
        name: String,
        password_mode: i32, // 0 = Keep, 1 = Set, 2 = Clear
        password: String,
        topic: String,
        description: String,
        max_clients: i32,
        channel_type: i32,
        is_default: bool,
        opus_music: bool,
        quality: u8,
        needed_talk_power: i32,
    },
    DeleteChannel {
        channel_id: u64,
        force: bool,
    },
    RequestChannelDescription {
        channel_id: u64,
    },
    MoveClient {
        client_id: u16,
        target_channel_id: u64,
        password: String,
    },
    KickClient {
        client_id: u16,
        from_server: bool,
        reason: String,
    },
    BanClient {
        client_id: u16,
        duration_secs: u64,
        reason: String,
    },
    DownloadFile {
        channel_id: u64,
        remote_path: String,
        file_key: String,
        dest_path: String,
    },
    SetupStream {
        name: String,
        stream_type: i32,
        bitrate_kbps: u32,
        accessibility: i32,
        mode: i32,
        viewer_limit: u32,
        audio: bool,
    },
    StopStream {
        stream_id: String,
    },
    RequestStreamInfo {
        client_id: u16,
    },
    JoinStreamRequest {
        stream_id: String,
        streamer_client_id: u16,
        message: String,
        is_remove: bool,
    },
    RespondJoinStreamRequest {
        stream_id: String,
        viewer_client_id: u16,
        decision: bool,
        offer: String,
        message: String,
    },
    SendStreamSignaling {
        stream_id: String,
        target_client_id: u16,
        json: String,
    },
    RemoveClientFromStream {
        stream_id: String,
        viewer_client_id: u16,
        reason: i32,
    },
    CancelAutoUpgrade,
    InternalAutoUpgradeFinished {
        session_id: i64,
        needed: u8,
        result: Result<IdentityInfoDto, String>,
    },
}

const MAX_ICON_DOWNLOAD_BYTES: u64 = 8 * 1024 * 1024;
static NEXT_EVENT_SEQ: AtomicU64 = AtomicU64::new(1);

fn next_event_id(prefix: &str) -> String {
    let seq = NEXT_EVENT_SEQ.fetch_add(1, Ordering::Relaxed);
    format!("{prefix}_{}_{seq}", now_ms())
}

struct PendingCmdMeta {
    action: String,
    target_channel_id: Option<i64>,
    silent_on_ok: bool,
    echo_chat: Option<ChatMessageDto>,
    is_create_channel: bool,
}

impl PendingCmdMeta {
    fn simple(action: impl Into<String>, target_channel_id: Option<i64>, silent_on_ok: bool) -> Self {
        Self {
            action: action.into(),
            target_channel_id,
            silent_on_ok,
            echo_chat: None,
            is_create_channel: false,
        }
    }
}

struct ConnectFallbackParams {
    raw_host: String,
    raw_port: u16,
    remaining_addrs: Vec<SocketAddr>,
    identity_ini: String,
    nickname: String,
    server_password: String,
    default_channel: String,
    channel_password: String,
}

struct ActiveSession {
    session_id: i64,
    display_address: String,
    my_nickname: String,
    target_addr: SocketAddr,
    identity_ini: String,
    server_password: String,
    default_channel: String,
    channel_password: String,
    connected_emitted: bool,
    local_mute_blocked: bool,
    con: Option<Connection>,
    upgrade_cancel: Option<Arc<AtomicBool>>,
    fallback: Option<ConnectFallbackParams>,
    pending_downloads: HashMap<FiletransferHandle, (String, String)>,
    pending_commands: HashMap<MessageHandle, PendingCmdMeta>,
    perm_names: HashMap<u32, String>,
    pending_auto_join: Option<(String, u64, String)>,
    stream_tracker: Ts6StreamTracker,
}

fn cancel_and_close_session(mut sess: ActiveSession, reason: String) {
    if let Some(cancel) = sess.upgrade_cancel.take() {
        cancel.store(true, Ordering::SeqCst);
    }
    if let Some(con) = sess.con.take() {
        tokio::spawn(gracefully_close_connection(con, reason));
    }
}

pub async fn run_session_actor(
    mut cmd_rx: mpsc::UnboundedReceiver<SessionCommand>,
    cmd_tx: mpsc::UnboundedSender<SessionCommand>,
    event_tx: mpsc::UnboundedSender<CoreEventEnvelope>,
    audio_engine: Arc<Mutex<RealtimeAudioEngine>>,
    global_security_cancel: Arc<AtomicBool>,
) {
    let mut active: Option<ActiveSession> = None;
    let mut stats_interval = tokio::time::interval(Duration::from_secs(2));
    stats_interval.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);

    loop {
        tokio::select! {
            maybe_cmd = cmd_rx.recv() => {
                let Some(cmd) = maybe_cmd else { break; };
                handle_command(cmd, &mut active, &event_tx, &audio_engine).await;
            }

            stream_opt = poll_active_event(&mut active), if active.as_ref().and_then(|a| a.con.as_ref()).is_some() => {
                handle_stream_item(
                    stream_opt,
                    &mut active,
                    &cmd_tx,
                    &event_tx,
                    &audio_engine,
                    &global_security_cancel,
                );
            }

            _ = stats_interval.tick(), if active.as_ref().map(|a| a.connected_emitted && a.con.is_some()).unwrap_or(false) => {
                if let Some(sess) = active.as_mut() {
                    emit_snapshot(sess, &event_tx, &audio_engine);
                    if let Some(con) = sess.con.as_mut() {
                        let _ = con.send_keepalive_ping();
                    }
                }
            }
        }
    }
}

async fn poll_active_event(
    active: &mut Option<ActiveSession>,
) -> Option<Result<StreamItem, tsclientlib::Error>> {
    let sess = active.as_mut()?;
    let con = sess.con.as_mut()?;
    con.events().next().await
}

async fn gracefully_close_connection(con: Connection, reason: String) {
    gracefully_close_connection_with_timeout(con, reason, Duration::from_millis(1800)).await;
}

async fn gracefully_close_connection_with_timeout(
    mut con: Connection,
    reason: String,
    max_wait: Duration,
) {
    if con.get_state().is_err() {
        // Still in initial handshake (`Connecting`): drop immediately so handshake aborts
        // instead of completing a new session after user already cancelled/closed.
        return;
    }
    let opts = DisconnectOptions::new()
        .reason(Reason::Clientdisconnect)
        .message(reason);
    if con.disconnect(opts).is_ok() {
        let _ = tokio::time::timeout(
            max_wait,
            con.events().for_each(|_| futures::future::ready(())),
        )
        .await;
    }
}

fn ts6_client_version() -> tsproto_types::versions::Version {
    let sig = base64::Engine::decode(
        &base64::engine::general_purpose::STANDARD,
        "b5mySiqYAd4Lv5TZNflW+G5Gk8y7Woo9YnJfHRjmzhUyMdGfF1O7fSGJzmx2Hpe/PKaY2aDuKKD6lSxbLYlKCw==",
    )
    .unwrap_or_default();
    tsproto_types::versions::Version::Custom {
        platform: "Windows".to_string(),
        version: "6.0.0-beta2 [Build: 1737468425]".to_string(),
        signature: sig,
    }
}

fn build_connection_for_addr(
    target_addr: SocketAddr,
    identity: tsclientlib::Identity,
    nickname: &str,
    server_password: &str,
    default_channel: &str,
    channel_password: &str,
) -> Result<Connection, tsclientlib::Error> {
    let mut opts = Connection::build(target_addr)
        .identity(identity)
        .version(ts6_client_version())
        .name(nickname.to_string());

    if !server_password.is_empty() {
        opts = opts.password(server_password.to_string());
    }
    if !default_channel.is_empty() {
        opts = opts.channel(default_channel.to_string());
    }
    if !channel_password.is_empty() {
        opts = opts.channel_password(channel_password.to_string());
    }
    opts.connect()
}

fn code_to_channel_type(code: i32, is_default: bool) -> ChannelType {
    if is_default {
        return ChannelType::Permanent;
    }
    match code {
        2 => ChannelType::Permanent,
        1 => ChannelType::SemiPermanent,
        _ => ChannelType::Temporary,
    }
}

async fn handle_command(
    cmd: SessionCommand,
    active: &mut Option<ActiveSession>,
    event_tx: &mpsc::UnboundedSender<CoreEventEnvelope>,
    audio_engine: &Arc<Mutex<RealtimeAudioEngine>>,
) {
    match cmd {
        SessionCommand::Connect {
            session_id,
            address,
            port,
            nickname,
            identity_ini,
            server_password,
            default_channel,
            channel_password,
            dns_servers_csv,
        } => {
            if let Some(prev) = active.take() {
                cancel_and_close_session(
                    prev,
                    if is_en() {
                        "Switching server".to_string()
                    } else {
                        "切换服务器".to_string()
                    },
                );
            }
            if let Ok(mut eng) = audio_engine.lock() {
                eng.reset_playback_queues();
                eng.set_server_can_send_audio(false);
            }

            emit_state(
                event_tx,
                "CONNECTING",
                Some(if is_en() {
                    format!("Resolving {address}...")
                } else {
                    format!("正在解析 {address}...")
                }),
            );

            let identity = match parse_identity_any(&identity_ini) {
                Ok(id) => id,
                Err(err) => {
                    emit_state(event_tx, "ERROR", Some(err));
                    return;
                }
            };

            let resolved_addrs = match resolve_teamspeak_server(&address, port, &dns_servers_csv).await {
                Ok(list) => list,
                Err(err) => {
                    emit_state(event_tx, "ERROR", Some(err));
                    return;
                }
            };

            let target_addr = resolved_addrs[0];
            let remaining_addrs = resolved_addrs.into_iter().skip(1).collect::<Vec<_>>();
            emit_state(
                event_tx,
                "CONNECTING",
                Some(if is_en() {
                    format!("Handshaking with {target_addr}...")
                } else {
                    format!("正在握手 {target_addr}...")
                }),
            );

            match build_connection_for_addr(
                target_addr,
                identity,
                &nickname,
                &server_password,
                &default_channel,
                &channel_password,
            ) {
                Ok(con) => {
                    *active = Some(ActiveSession {
                        session_id,
                        display_address: format!("{address}:{port}"),
                        my_nickname: nickname.clone(),
                        target_addr,
                        identity_ini: identity_ini.clone(),
                        server_password: server_password.clone(),
                        default_channel: default_channel.clone(),
                        channel_password: channel_password.clone(),
                        connected_emitted: false,
                        local_mute_blocked: false,
                        con: Some(con),
                        upgrade_cancel: None,
                        fallback: Some(ConnectFallbackParams {
                            raw_host: address,
                            raw_port: port,
                            remaining_addrs,
                            identity_ini,
                            nickname,
                            server_password,
                            default_channel,
                            channel_password,
                        }),
                        pending_downloads: HashMap::new(),
                        pending_commands: HashMap::new(),
                        perm_names: HashMap::new(),
                        pending_auto_join: None,
                        stream_tracker: Ts6StreamTracker::default(),
                    });
                }
                Err(err) => {
                    invalidate_dns_cache(&address, port);
                    emit_state(
                        event_tx,
                        "ERROR",
                        Some(if is_en() {
                            format!("Connection init failed: {}", format_connect_error(&err))
                        } else {
                            format!("连接初始化失败: {}", format_connect_error(&err))
                        }),
                    );
                }
            }
        }

        SessionCommand::Disconnect { reason } => {
            if let Some(sess) = active.take() {
                cancel_and_close_session(sess, reason.clone());
            }
            if let Ok(mut eng) = audio_engine.lock() {
                eng.reset_playback_queues();
                eng.set_server_can_send_audio(false);
            }
            emit_state(event_tx, "DISCONNECTED", Some(reason));
        }

        SessionCommand::CancelAutoUpgrade => {
            if let Some(sess) = active.as_ref() {
                if let Some(ref cancel) = sess.upgrade_cancel {
                    cancel.store(true, Ordering::SeqCst);
                }
            }
        }

        SessionCommand::InternalAutoUpgradeFinished {
            session_id,
            needed,
            result,
        } => {
            let matches_active = active
                .as_ref()
                .map(|s| s.session_id == session_id)
                .unwrap_or(false);
            if !matches_active {
                // Even if session already disconnected, persist any earned PoW upgrade
                if let Ok(dto) = result {
                    if dto.security_level > 8 {
                        let _ = event_tx.send(CoreEventEnvelope {
                            kind: "identity_upgraded".to_string(),
        SessionCommand::DisconnectSync {
            reason,
            timeout_ms,
            done_tx,
        } => {
            if let Ok(mut eng) = audio_engine.lock() {
                eng.reset_playback_queues();
                eng.set_server_can_send_audio(false);
            }
            if let Some(mut sess) = active.take() {
                if let Some(cancel) = sess.upgrade_cancel.take() {
                    cancel.store(true, Ordering::SeqCst);
                }
                emit_state(event_tx, "DISCONNECTED", Some(reason.clone()));
                if let Some(con) = sess.con.take() {
                    let wait = Duration::from_millis(timeout_ms.clamp(50, 1500));
                    gracefully_close_connection_with_timeout(con, reason, wait).await;
                }
            }
            let _ = done_tx.send(());
        }

                            security_level: Some(dto.security_level),
                            key_offset: Some(dto.key_offset),
                            updated_ini: Some(dto.ini_content),
                            unique_id: Some(dto.unique_id),
                            ..Default::default()
                        });
                    }
                }
                return;
            }

            match result {
                Ok(dto) => {
                    let _ = event_tx.send(CoreEventEnvelope {
                        kind: "identity_upgraded".to_string(),
                        security_level: Some(dto.security_level),
                        key_offset: Some(dto.key_offset),
                        updated_ini: Some(dto.ini_content.clone()),
                        unique_id: Some(dto.unique_id),
                        ..Default::default()
                    });

                    if dto.cancelled || dto.security_level < i32::from(needed) {
                        active.take();
                        emit_state(
                            event_tx,
                            "DISCONNECTED",
                            Some(if is_en() {
                                format!(
                                    "Security upgrade paused at Lv.{} (server requires Lv.{needed})",
                                    dto.security_level
                                )
                            } else {
                                format!(
                                    "已暂停安全等级提升（当前已保存至 Lv.{}，该服要求 Lv.{needed}）",
                                    dto.security_level
                                )
                            }),
                        );
                        return;
                    }

                    if let Some(sess) = active.as_mut() {
                        sess.upgrade_cancel = None;
                        sess.identity_ini = dto.ini_content.clone();
                        emit_state(
                            event_tx,
                            "CONNECTING",
                            Some(if is_en() {
                                format!("Security upgraded to Lv.{}, reconnecting...", dto.security_level)
                            } else {
                                format!("已达 Lv.{}，正在建立连接...", dto.security_level)
                            }),
                        );
                        match parse_identity_any(&dto.ini_content).and_then(|upgraded_id| {
                            build_connection_for_addr(
                                sess.target_addr,
                                upgraded_id,
                                &sess.my_nickname,
                                &sess.server_password,
                                &sess.default_channel,
                                &sess.channel_password,
                            )
                            .map_err(|e| format_connect_error(&e))
                        }) {
                            Ok(new_con) => {
                                sess.con = Some(new_con);
                            }
                            Err(err_msg) => {
                                active.take();
                                emit_state(event_tx, "ERROR", Some(err_msg));
                            }
                        }
                    }
                }
                Err(err_msg) => {
                    active.take();
                    emit_state(event_tx, "ERROR", Some(err_msg));
                }
            }
        }

        SessionCommand::SwitchChannel {
            channel_id,
            password,
        } => {
            let Some(sess) = active.as_mut() else { return };
            let Some(con) = sess.con.as_mut() else { return };
            let cmd_res = {
                let Ok(state) = con.get_state() else { return };
                let Some(me) = state.clients.get(&state.own_client) else { return };
                if me.channel.0 == channel_id {
                    return;
                }
                let mut mv = me.client_move(ChannelId(channel_id));
                if !password.is_empty() {
                    mv = mv.set_password(&password);
                }
                mv.to_packet()
            };
            if let Ok(h) = cmd_res.send_with_result(con) {
                sess.pending_commands.insert(
                    h,
                    PendingCmdMeta::simple(
                        if is_en() { "Switch channel" } else { "切换频道" },
                        Some(channel_id as i64),
                        true,
                    ),
                );
            }
        }

        SessionCommand::SendMessage {
            target_mode,
            target_id,
            message,
        } => {
            let Some(sess) = active.as_mut() else { return };
            let Some(con) = sess.con.as_mut() else { return };
            let (pkt, echo_dto) = {
                let Ok(state) = con.get_state() else { return };
                let my_id = i32::from(state.own_client.0);
                let my_uid = state
                    .clients
                    .get(&state.own_client)
                    .and_then(|c| c.uid.as_ref().map(|u| u.0.as_slice()))
                    .map(|b| base64::Engine::encode(&base64::engine::general_purpose::STANDARD, b))
                    .unwrap_or_default();

                let (mt, target_str, peer_cid, peer_uid_opt, peer_name) = match target_mode {
                    1 => {
                        let cid = ClientId(target_id as u16);
                        let peer_cl = state.clients.get(&cid);
                        let pname = peer_cl.map(|c| c.name.clone()).unwrap_or_default();
                        let puid = peer_cl
                            .and_then(|c| c.uid.as_ref().map(|u| u.0.as_slice()))
                            .map(|b| {
                                base64::Engine::encode(&base64::engine::general_purpose::STANDARD, b)
                            });
                        (
                            MessageTarget::Client(cid),
                            "CLIENT".to_string(),
                            Some(i32::from(cid.0)),
                            puid,
                            pname,
                        )
                    }
                    2 => (
                        MessageTarget::Channel,
                        "CHANNEL".to_string(),
                        None,
                        None,
                        String::new(),
                    ),
                    _ => (
                        MessageTarget::Server,
                        "SERVER".to_string(),
                        None,
                        None,
                        String::new(),
                    ),
                };

                let echo = if matches!(mt, MessageTarget::Client(_)) {
                    Some(ChatMessageDto {
                        id: next_event_id("out"),
                        target: target_str,
                        peer_client_id: peer_cid,
                        peer_uid: peer_uid_opt,
                        peer_name,
                        sender_id: my_id,
                        sender_name: sess.my_nickname.clone(),
                        sender_uid: my_uid,
                        content: message.clone(),
                        timestamp_ms: now_ms(),
                        is_system: false,
                    })
                } else {
                    None
                };

                (state.send_message(mt, &message), echo)
            };

            if let Ok(h) = pkt.send_with_result(con) {
                sess.pending_commands.insert(
                    h,
                    PendingCmdMeta {
                        action: if is_en() {
                            "Send message".to_string()
                        } else {
                            "发送消息".to_string()
                        },
                        target_channel_id: None,
                        silent_on_ok: true,
                        echo_chat: echo_dto,
                        is_create_channel: false,
                    },
                );
            }
        }

        SessionCommand::SetSelfMute {
            input_muted,
            output_muted,
            away,
            away_message,
        } => {
            let Some(sess) = active.as_mut() else { return };
            sess.local_mute_blocked = input_muted || output_muted || away;
            if let Some(con) = sess.con.as_mut() {
                let pkt_opt = con.get_state().ok().map(|state| {
                    let away_opt = if away {
                        Some(away_message.as_str())
                    } else {
                        None
                    };
                    state
                        .client_update()
                        .set_input_muted(input_muted)
                        .set_output_muted(output_muted)
                        .set_away(away_opt)
                        .to_packet()
                });
                if let Some(pkt) = pkt_opt {
                    let _ = pkt.send(con);
                }
                if let Ok(mut eng) = audio_engine.lock() {
                    eng.set_server_can_send_audio(!sess.local_mute_blocked && con.can_send_audio());
                }
            }
            emit_snapshot(sess, event_tx, audio_engine);
        }

        SessionCommand::SendAudio(packet) => {
            if let Some(sess) = active.as_mut() {
                if let Some(con) = sess.con.as_mut() {
                    if !sess.local_mute_blocked && con.can_send_audio() {
                        let _ = con.send_audio(packet);
                    }
                }
            }
        }

        SessionCommand::SendPoke { client_id, message } => {
            let Some(sess) = active.as_mut() else { return };
            let Some(con) = sess.con.as_mut() else { return };
            let pkt = {
                let Ok(state) = con.get_state() else { return };
                state.send_message(MessageTarget::Poke(ClientId(client_id)), &message)
            };
            if let Ok(h) = pkt.send_with_result(con) {
                sess.pending_commands.insert(
                    h,
                    PendingCmdMeta::simple(
                        if is_en() { "Send poke" } else { "发送戳一戳" },
                        None,
                        false,
                    ),
                );
            }
        }

        SessionCommand::UsePrivilegeKey { token } => {
            let Some(sess) = active.as_mut() else { return };
            let Some(con) = sess.con.as_mut() else { return };
            let pkt = c2s::OutTokenUsePart {
                token: Cow::Borrowed(&token),
            }
            .to_packet();
            if let Ok(h) = pkt.send_with_result(con) {
                sess.pending_commands.insert(
                    h,
                    PendingCmdMeta::simple(
                        if is_en() { "Redeem privilege key" } else { "激活权限密钥" },
                        None,
                        false,
                    ),
                );
            }
        }

        SessionCommand::RequestTalkPower { request, message } => {
            let Some(sess) = active.as_mut() else { return };
            let Some(con) = sess.con.as_mut() else { return };
            let mut pkt = OutCommand::new(
                Direction::C2S,
                Flags::empty(),
                PacketType::Command,
                "clientupdate",
            );
            pkt.write_arg("client_talk_request", &(if request { 1u8 } else { 0u8 }));
            pkt.write_arg("client_talk_request_msg", &message);
            if let Ok(h) = con.send_raw_command_with_result(pkt) {
                sess.pending_commands.insert(
                    h,
                    PendingCmdMeta::simple(
                        if request {
                            if is_en() { "Request talk power" } else { "申请发言权" }
                        } else if is_en() {
                            "Cancel talk request"
                        } else {
                            "取消发言申请"
                        },
                        None,
                        false,
                    ),
                );
            }
        }

        SessionCommand::CreateChannel {
            name,
            parent_id,
            password,
            topic,
            description,
            max_clients,
            channel_type,
            is_default,
            opus_music,
            quality,
            needed_talk_power,
            auto_join,
        } => {
            let Some(sess) = active.as_mut() else { return };
            let Some(con) = sess.con.as_mut() else { return };
            let ch_type = code_to_channel_type(channel_type, is_default);
            let pkt = {
                let Ok(state) = con.get_state() else { return };
                let mut opts = ChannelOptions::new(&name)
                    .codec(if opus_music {
                        Codec::OpusMusic
                    } else {
                        Codec::OpusVoice
                    })
                    .codec_quality(quality)
                    .channel_type(ch_type);
                if is_default {
                    opts = opts.default();
                }
                if parent_id > 0 {
                    opts = opts.parent_id(ChannelId(parent_id));
                }
                if !password.is_empty() {
                    opts = opts.password(&password);
                }
                if !topic.is_empty() {
                    opts = opts.topic(&topic);
                }
                if !description.is_empty() {
                    opts = opts.description(&description);
                }
                if max_clients > 0 {
                    opts = opts.max_clients(MaxClients::Limited(max_clients as u16));
                } else if ch_type != ChannelType::Temporary {
                    opts = opts.max_clients(MaxClients::Unlimited);
                }
                let mut cmd = state.server.add_channel(opts);
                if needed_talk_power > 0 {
                    cmd.write_arg("channel_needed_talk_power", &needed_talk_power);
                }
                cmd
            };

            if auto_join && ch_type != ChannelType::Temporary {
                sess.pending_auto_join = Some((name.clone(), parent_id, password.clone()));
            }

            if let Ok(h) = pkt.send_with_result(con) {
                sess.pending_commands.insert(
                    h,
                    PendingCmdMeta {
                        action: if is_en() {
                            "Create channel".to_string()
                        } else {
                            "创建频道".to_string()
                        },
                        target_channel_id: None,
                        silent_on_ok: false,
                        echo_chat: None,
                        is_create_channel: true,
                    },
                );
            }
        }

        SessionCommand::EditChannel {
            channel_id,
            parent_id,
            name,
            password_mode,
            password,
            topic,
            description,
            max_clients,
            channel_type,
            is_default,
            opus_music,
            quality,
            needed_talk_power,
        } => {
            let Some(sess) = active.as_mut() else { return };
            let Some(con) = sess.con.as_mut() else { return };
            let (edit_pkt_opt, move_pkt_opt) = {
                let Ok(state) = con.get_state() else { return };
                let Some(ch) = state.channels.get(&ChannelId(channel_id)) else { return };

                let mut has_edit = false;
                let mut edit = ch.edit();

                if !name.is_empty() && name != ch.name {
                    edit = edit.set_name(&name);
                    has_edit = true;
                }

                match password_mode {
                    1 => {
                        edit = edit.set_password(Some(&password));
                        has_edit = true;
                    }
                    2 => {
                        if ch.has_password == Some(true) {
                            edit = edit.set_password(None);
                            has_edit = true;
                        }
                    }
                    _ => {}
                }

                let cur_topic = ch.topic.as_deref().unwrap_or("");
                if topic != cur_topic {
                    edit = edit.set_topic(&topic);
                    has_edit = true;
                }

                let cur_desc = ch.optional_data.as_ref().map(|d| d.description.as_str());
                let desc_changed = match cur_desc {
                    Some(existing) => existing != description,
                    None => !description.is_empty(),
                };
                if desc_changed {
                    edit = edit.set_description(&description);
                    has_edit = true;
                }

                let want_max = if max_clients > 0 {
                    MaxClients::Limited(max_clients as u16)
                } else {
                    MaxClients::Unlimited
                };
                if ch.max_clients != Some(want_max) {
                    edit = edit.set_max_clients(want_max);
                    has_edit = true;
                }

                let want_type = code_to_channel_type(channel_type, is_default);
                if ch.channel_type != want_type {
                    edit = edit.set_channel_type(want_type);
                    has_edit = true;
                }

                if is_default && ch.is_default != Some(true) {
                    edit = edit.set_is_default(true);
                    has_edit = true;
                }

                let want_codec = if opus_music {
                    Codec::OpusMusic
                } else {
                    Codec::OpusVoice
                };
                if ch.codec != want_codec {
                    edit = edit.set_codec(want_codec);
                    has_edit = true;
                }

                if ch.codec_quality.unwrap_or(6) != quality {
                    edit = edit.set_codec_quality(quality);
                    has_edit = true;
                }

                if ch.needed_talk_power.unwrap_or(0) != needed_talk_power {
                    edit = edit.set_needed_talk_power(needed_talk_power);
                    has_edit = true;
                }

                let move_pkt = if parent_id >= 0 && (parent_id as u64) != ch.parent.0 {
                    Some(ch.channel_move(ChannelId(parent_id as u64), ChannelId(0)).to_packet())
                } else {
                    None
                };

                let edit_pkt = if has_edit {
                    Some(edit.to_packet())
                } else {
                    None
                };

                (edit_pkt, move_pkt)
            };

            if edit_pkt_opt.is_none() && move_pkt_opt.is_none() {
                let _ = event_tx.send(CoreEventEnvelope {
                    kind: "command_result".to_string(),
                    action: Some(if is_en() {
                        "Edit channel".to_string()
                    } else {
                        "编辑频道".to_string()
                    }),
                    command_ok: Some(true),
                    reason: Some(if is_en() {
                        "Channel properties unchanged".to_string()
                    } else {
                        "频道属性未变更".to_string()
                    }),
                    target_channel_id: Some(channel_id as i64),
                    ..Default::default()
                });
                return;
            }

            if let Some(mv_pkt) = move_pkt_opt {
                let silent = edit_pkt_opt.is_some();
                if let Ok(h) = mv_pkt.send_with_result(con) {
                    sess.pending_commands.insert(
                        h,
                        PendingCmdMeta::simple(
                            if is_en() { "Move channel" } else { "移动频道层级" },
                            Some(channel_id as i64),
                            silent,
                        ),
                    );
                }
            }

            if let Some(ed_pkt) = edit_pkt_opt {
                if let Ok(h) = ed_pkt.send_with_result(con) {
                    sess.pending_commands.insert(
                        h,
                        PendingCmdMeta::simple(
                            if is_en() { "Edit channel" } else { "编辑频道" },
                            Some(channel_id as i64),
                            false,
                        ),
                    );
                }
            }
        }

        SessionCommand::DeleteChannel { channel_id, force } => {
            let Some(sess) = active.as_mut() else { return };
            let Some(con) = sess.con.as_mut() else { return };
            let mut pkt = OutCommand::new(
                Direction::C2S,
                Flags::empty(),
                PacketType::Command,
                "channeldelete",
            );
            pkt.write_arg("cid", &channel_id);
            pkt.write_arg("force", &(if force { 1u8 } else { 0u8 }));
            if let Ok(h) = pkt.send_with_result(con) {
                sess.pending_commands.insert(
                    h,
                    PendingCmdMeta::simple(
                        if is_en() { "Delete channel" } else { "删除频道" },
                        Some(channel_id as i64),
                        false,
                    ),
                );
            }
        }

        SessionCommand::RequestChannelDescription { channel_id } => {
            let Some(sess) = active.as_mut() else { return };
            let Some(con) = sess.con.as_mut() else { return };
            let pkt_opt = {
                let Ok(state) = con.get_state() else { return };
                state
                    .channels
                    .get(&ChannelId(channel_id))
                    .map(|ch| ch.description().to_packet())
            };
            if let Some(pkt) = pkt_opt {
                let _ = pkt.send(con);
            }
        }

        SessionCommand::MoveClient {
            client_id,
            target_channel_id,
            password,
        } => {
            let Some(sess) = active.as_mut() else { return };
            let Some(con) = sess.con.as_mut() else { return };
            let pkt = {
                let Ok(state) = con.get_state() else { return };
                let Some(cl) = state.clients.get(&ClientId(client_id)) else { return };
                let mut mv = cl.client_move(ChannelId(target_channel_id));
                if !password.is_empty() {
                    mv = mv.set_password(&password);
                }
                mv.to_packet()
            };
            if let Ok(h) = pkt.send_with_result(con) {
                sess.pending_commands.insert(
                    h,
                    PendingCmdMeta::simple(
                        if is_en() { "Move member" } else { "移动成员" },
                        Some(target_channel_id as i64),
                        false,
                    ),
                );
            }
        }

        SessionCommand::KickClient {
            client_id,
            from_server,
            reason,
        } => {
            let Some(sess) = active.as_mut() else { return };
            let Some(con) = sess.con.as_mut() else { return };
            let pkt = {
                let Ok(state) = con.get_state() else { return };
                let Some(cl) = state.clients.get(&ClientId(client_id)) else { return };
                let r = if from_server {
                    Reason::KickServer
                } else {
                    Reason::KickChannel
                };
                let mut k = cl.kick(r);
                if !reason.is_empty() {
                    k = k.set_reason_message(&reason);
                }
                k.to_packet()
            };
            if let Ok(h) = pkt.send_with_result(con) {
                sess.pending_commands.insert(
                    h,
                    PendingCmdMeta::simple(
                        if from_server {
                            if is_en() { "Kick from server" } else { "踢出服务器" }
                        } else if is_en() {
                            "Kick from channel"
                        } else {
                            "移出当前频道"
                        },
                        None,
                        false,
                    ),
                );
            }
        }

        SessionCommand::BanClient {
            client_id,
            duration_secs,
            reason,
        } => {
            let Some(sess) = active.as_mut() else { return };
            let Some(con) = sess.con.as_mut() else { return };
            let mut pkt = OutCommand::new(
                Direction::C2S,
                Flags::empty(),
                PacketType::Command,
                "banclient",
            );
            pkt.write_arg("clid", &client_id);
            if duration_secs > 0 {
                pkt.write_arg("time", &duration_secs);
            }
            if !reason.is_empty() {
                pkt.write_arg("banreason", &reason);
            }
            if let Ok(h) = pkt.send_with_result(con) {
                sess.pending_commands.insert(
                    h,
                    PendingCmdMeta::simple(
                        if is_en() { "Ban member" } else { "封禁成员" },
                        None,
                        false,
                    ),
                );
            }
        }

        SessionCommand::DownloadFile {
            channel_id,
            remote_path,
            file_key,
            dest_path,
        } => {
            let Some(sess) = active.as_mut() else { return };
            let Some(con) = sess.con.as_mut() else { return };
            if let Ok(ft_handle) =
                con.download_file(ChannelId(channel_id), &remote_path, None, None)
            {
                sess.pending_downloads
                    .insert(ft_handle, (file_key, dest_path));
            }
        }

        SessionCommand::SetupStream {
            name,
            stream_type,
            bitrate_kbps,
            accessibility,
            mode,
            viewer_limit,
            audio,
        } => {
            let Some(sess) = active.as_mut() else { return };
            let Some(con) = sess.con.as_mut() else { return };
            let pkt = build_setup_stream_cmd(
                &name,
                stream_type,
                bitrate_kbps,
                accessibility,
                mode,
                viewer_limit,
                audio,
            );
            if let Ok(h) = con.send_raw_command_with_result(pkt) {
                sess.pending_commands.insert(
                    h,
                    PendingCmdMeta::simple(
                        if is_en() { "Start TS6 Live Stream" } else { "开启 TS6 直播" },
                        None,
                        true,
                    ),
                );
            }
        }

        SessionCommand::StopStream { stream_id } => {
            let Some(sess) = active.as_mut() else { return };
            let Some(con) = sess.con.as_mut() else { return };
            let pkt = build_stop_stream_cmd(&stream_id, 0);
            if let Ok(h) = con.send_raw_command_with_result(pkt) {
                sess.pending_commands.insert(
                    h,
                    PendingCmdMeta::simple(
                        if is_en() { "Stop stream" } else { "结束直播" },
                        None,
                        true,
                    ),
                );
            }
        }

        SessionCommand::RequestStreamInfo { client_id } => {
            let Some(sess) = active.as_mut() else { return };
            let Some(con) = sess.con.as_mut() else { return };
            let pkt = build_request_stream_info_cmd(client_id);
            let _ = con.send_raw_command(pkt);
        }

        SessionCommand::JoinStreamRequest {
            stream_id,
            streamer_client_id,
            message,
            is_remove,
        } => {
            let Some(sess) = active.as_mut() else { return };
            let Some(con) = sess.con.as_mut() else { return };
            let pkt =
                build_join_stream_request_cmd(&stream_id, streamer_client_id, &message, is_remove);
            if let Ok(h) = con.send_raw_command_with_result(pkt) {
                sess.pending_commands.insert(
                    h,
                    PendingCmdMeta::simple(
                        if is_remove {
                            if is_en() { "Leave stream" } else { "退出观看直播" }
                        } else if is_en() {
                            "Watch stream"
                        } else {
                            "请求观看直播"
                        },
                        None,
                        true,
                    ),
                );
            }
        }

        SessionCommand::RespondJoinStreamRequest {
            stream_id,
            viewer_client_id,
            decision,
            offer,
            message,
        } => {
            let Some(sess) = active.as_mut() else { return };
            let Some(con) = sess.con.as_mut() else { return };
            let pkt = build_respond_join_stream_cmd(
                &stream_id,
                viewer_client_id,
                decision,
                &offer,
                &message,
            );
            if let Ok(h) = con.send_raw_command_with_result(pkt) {
                sess.pending_commands.insert(
                    h,
                    PendingCmdMeta::simple(
                        if is_en() { "Respond stream join" } else { "响应直播接入" },
                        None,
                        true,
                    ),
                );
            }
        }

        SessionCommand::SendStreamSignaling {
            stream_id,
            target_client_id,
            json,
        } => {
            let Some(sess) = active.as_mut() else { return };
            let Some(con) = sess.con.as_mut() else { return };
            let pkt = build_stream_signaling_cmd(&stream_id, target_client_id, &json);
            let _ = con.send_raw_command(pkt);
        }

        SessionCommand::RemoveClientFromStream {
            stream_id,
            viewer_client_id,
            reason,
        } => {
            let Some(sess) = active.as_mut() else { return };
            let Some(con) = sess.con.as_mut() else { return };
            let pkt = build_remove_client_from_stream_cmd(&stream_id, viewer_client_id, reason);
            let _ = con.send_raw_command(pkt);
        }
    }
}

fn handle_stream_item(
    stream_opt: Option<Result<StreamItem, tsclientlib::Error>>,
    active: &mut Option<ActiveSession>,
    cmd_tx: &mpsc::UnboundedSender<SessionCommand>,
    event_tx: &mpsc::UnboundedSender<CoreEventEnvelope>,
    audio_engine: &Arc<Mutex<RealtimeAudioEngine>>,
    global_security_cancel: &Arc<AtomicBool>,
) {
    let Some(item_res) = stream_opt else {
        active.take();
        if let Ok(mut eng) = audio_engine.lock() {
            eng.reset_playback_queues();
        }
        emit_state(
            event_tx,
            "DISCONNECTED",
            Some(if is_en() {
                "Disconnected from server".to_string()
            } else {
                "已与服务器断开连接".to_string()
            }),
        );
        return;
    };

    let item = match item_res {
        Ok(it) => it,
        Err(tsclientlib::Error::IdentityLevel(needed)) => {
            active.take();
            emit_state(
                event_tx,
                "ERROR",
                Some(if is_en() {
                    format!(
                        "Server requires Identity Security Level ≥ Lv.{needed}. Upgrade your key in Settings → Identity Keys."
                    )
                } else {
                    format!(
                        "该服务器要求身份安全等级至少达到 Lv.{needed}，请前往「设置 → 身份密钥」提升安全等级后再连接"
                    )
                }),
            );
            return;
        }
        Err(err) => {
            // Try next resolved IP fallback if initial handshake timed out on first candidate
            let can_try_next = active
                .as_ref()
                .map(|s| !s.connected_emitted && !matches!(err, tsclientlib::Error::ConnectTs(_)))
                .unwrap_or(false);

            if can_try_next {
                if let Some(mut sess) = active.take() {
                    if let Some(mut fb) = sess.fallback.take() {
                        if !fb.remaining_addrs.is_empty() {
                            let next_addr = fb.remaining_addrs.remove(0);
                            if let Ok(id) = parse_identity_any(&fb.identity_ini) {
                                if let Ok(next_con) = build_connection_for_addr(
                                    next_addr,
                                    id,
                                    &fb.nickname,
                                    &fb.server_password,
                                    &fb.default_channel,
                                    &fb.channel_password,
                                ) {
                                    emit_state(
                                        event_tx,
                                        "CONNECTING",
                                        Some(if is_en() {
                                            format!("Trying fallback address {next_addr}...")
                                        } else {
                                            format!("正在尝试备用线路 {next_addr}...")
                                        }),
                                    );
                                    sess.target_addr = next_addr;
                                    sess.con = Some(next_con);
                                    sess.fallback = Some(fb);
                                    *active = Some(sess);
                                    return;
                                }
                            }
                        } else {
                            invalidate_dns_cache(&fb.raw_host, fb.raw_port);
                        }
                    }
                }
            } else if let Some(prev) = active.take() {
                if let Some(fb) = prev.fallback {
                    if !prev.connected_emitted && !matches!(err, tsclientlib::Error::ConnectTs(_)) {
                        invalidate_dns_cache(&fb.raw_host, fb.raw_port);
                    }
                }
            }

            if let Ok(mut eng) = audio_engine.lock() {
                eng.reset_playback_queues();
                eng.set_server_can_send_audio(false);
            }
            emit_state(event_tx, "ERROR", Some(format_connect_error(&err)));
            return;
        }
    };

    let Some(sess) = active.as_mut() else { return };

    match item {
        StreamItem::BookEvents(events) => {
            let was_already_connected = sess.connected_emitted;
            if !sess.connected_emitted {
                sess.connected_emitted = true;
                sess.fallback = None;
                if let Some(con) = sess.con.as_mut() {
                    let init_info = con.get_state().ok().map(|state| {
                        (
                            state.server.set_subscribed(true),
                            state.server.perm_list(),
                            state.server.name.clone(),
                            state.server.welcome_message.clone(),
                        )
                    });
                    if let Some((sub_pkt, perm_pkt, srv_name, welcome_msg)) = init_info {
                        let _ = sub_pkt.send(con);
                        let _ = perm_pkt.send(con);
                        let _ = con.send_keepalive_ping();

                        if !welcome_msg.is_empty() {
                            let _ = event_tx.send(CoreEventEnvelope {
                                kind: "chat".to_string(),
                                chat: Some(ChatMessageDto {
                                    id: next_event_id("welcome"),
                                    target: "SERVER".to_string(),
                                    peer_client_id: None,
                                    peer_uid: None,
                                    peer_name: String::new(),
                                    sender_id: 0,
                                    sender_name: srv_name,
                                    sender_uid: String::new(),
                                    content: welcome_msg,
                                    timestamp_ms: now_ms(),
                                    is_system: true,
                                }),
                                ..Default::default()
                            });
                        }
                    }
                }
                emit_state(event_tx, "CONNECTED", None);
            }

            let (own_cid, cur_my_ch) = sess
                .con
                .as_ref()
                .and_then(|con| con.get_state().ok())
                .map(|st| {
                    let ch = st
                        .clients
                        .get(&st.own_client)
                        .map(|c| c.channel)
                        .unwrap_or(ChannelId(0));
                    (st.own_client, ch)
                })
                .unwrap_or((ClientId(0), ChannelId(0)));

            let mut room_cue: Option<&'static str> = None;
            let mut auto_move_target: Option<(ChannelId, String)> = None;

            for ev in events {
                match ev {
                    Event::PropertyChanged {
                        id: PropertyId::ClientChannel(cid),
                        old: PropertyValue::ChannelId(old_ch),
                        ..
                    } if was_already_connected => {
                        if cid == own_cid {
                            if let Ok(mut eng) = audio_engine.lock() {
                                eng.reset_playback_queues();
                            }
                            room_cue = Some("self_switch");
                        } else if room_cue != Some("self_switch") {
                            let new_ch = sess
                                .con
                                .as_ref()
                                .and_then(|con| con.get_state().ok())
                                .and_then(|st| st.clients.get(&cid).map(|c| c.channel));
                            if new_ch == Some(cur_my_ch) && old_ch != cur_my_ch {
                                room_cue = Some("peer_join");
                            } else if old_ch == cur_my_ch && new_ch != Some(cur_my_ch) {
                                room_cue = Some("peer_leave");
                            }
                        }
                    }

                    Event::PropertyAdded {
                        id: PropertyId::Client(cid),
                        extra,
                        ..
                    } if was_already_connected => {
                        if cid != own_cid
                            && extra.reason.is_some()
                            && extra.reason != Some(Reason::Subscription)
                            && room_cue.is_none()
                        {
                            let in_my_ch = sess
                                .con
                                .as_ref()
                                .and_then(|con| con.get_state().ok())
                                .and_then(|st| st.clients.get(&cid).map(|c| c.channel == cur_my_ch))
                                .unwrap_or(false);
                            if in_my_ch {
                                room_cue = Some("peer_join");
                            }
                        }
                    }

                    Event::PropertyRemoved {
                        id: PropertyId::Client(cid),
                        old: PropertyValue::Client(old_c),
                        extra,
                        ..
                    } => {
                        if let Some(sig) = sess.stream_tracker.on_client_removed(cid.0) {
                            let _ = event_tx.send(CoreEventEnvelope {
                                kind: "ts6_stream".to_string(),
                                stream_signal: Some(sig),
                                ..Default::default()
                            });
                        }
                        if was_already_connected
                            && cid != own_cid
                            && extra.reason != Some(Reason::Subscription)
                            && old_c.channel == cur_my_ch
                            && room_cue.is_none()
                        {
                            room_cue = Some("peer_leave");
                        }
                    }

                    Event::PropertyAdded {
                        id: PropertyId::Channel(new_cid),
                        ..
                    } => {
                        if let Some((ref want_name, want_parent, ref want_pwd)) = sess.pending_auto_join {
                            let matched = sess
                                .con
                                .as_ref()
                                .and_then(|con| con.get_state().ok())
                                .and_then(|st| {
                                    st.channels
                                        .get(&new_cid)
                                        .map(|c| &c.name == want_name && c.parent.0 == want_parent)
                                })
                                .unwrap_or(false);
                            if matched {
                                auto_move_target = Some((new_cid, want_pwd.clone()));
                            }
                        }
                    }

                    Event::Message {
                        target,
                        invoker,
                        message,
                    } => {
                        let uid_str = invoker
                            .uid
                            .as_ref()
                            .map(|u| {
                                base64::Engine::encode(
                                    &base64::engine::general_purpose::STANDARD,
                                    u.0.as_slice(),
                                )
                            })
                            .unwrap_or_default();
                        match target {
                            MessageTarget::Poke(_) => {
                                let _ = event_tx.send(CoreEventEnvelope {
                                    kind: "poke".to_string(),
                                    poke: Some(PokeAlertDto {
                                        id: next_event_id(&format!("poke_{}", invoker.id.0)),
                                        from_client_id: i32::from(invoker.id.0),
                                        from_name: invoker.name,
                                        from_uid: uid_str,
                                        message,
                                        timestamp_ms: now_ms(),
                                    }),
                                    ..Default::default()
                                });
                            }
                            MessageTarget::Server => {
                                let _ = event_tx.send(CoreEventEnvelope {
                                    kind: "chat".to_string(),
                                    chat: Some(ChatMessageDto {
                                        id: next_event_id(&format!("msg_{}", invoker.id.0)),
                                        target: "SERVER".to_string(),
                                        peer_client_id: None,
                                        peer_uid: None,
                                        peer_name: String::new(),
                                        sender_id: i32::from(invoker.id.0),
                                        sender_name: invoker.name,
                                        sender_uid: uid_str,
                                        content: message,
                                        timestamp_ms: now_ms(),
                                        is_system: false,
                                    }),
                                    ..Default::default()
                                });
                            }
                            MessageTarget::Channel => {
                                let _ = event_tx.send(CoreEventEnvelope {
                                    kind: "chat".to_string(),
                                    chat: Some(ChatMessageDto {
                                        id: next_event_id(&format!("msg_{}", invoker.id.0)),
                                        target: "CHANNEL".to_string(),
                                        peer_client_id: None,
                                        peer_uid: None,
                                        peer_name: String::new(),
                                        sender_id: i32::from(invoker.id.0),
                                        sender_name: invoker.name,
                                        sender_uid: uid_str,
                                        content: message,
                                        timestamp_ms: now_ms(),
                                        is_system: false,
                                    }),
                                    ..Default::default()
                                });
                            }
                            MessageTarget::Client(target_cid) => {
                                if invoker.id == own_cid && target_cid != own_cid {
                                    continue;
                                }
                                let peer = if invoker.id == own_cid {
                                    target_cid
                                } else {
                                    invoker.id
                                };
                                let (peer_uid_resolved, peer_name_resolved) = if invoker.id == own_cid {
                                    sess.con
                                        .as_ref()
                                        .and_then(|con| con.get_state().ok())
                                        .and_then(|st| {
                                            st.clients.get(&peer).map(|c| {
                                                let puid = c
                                                    .uid
                                                    .as_ref()
                                                    .map(|u| {
                                                        base64::Engine::encode(
                                                            &base64::engine::general_purpose::STANDARD,
                                                            u.0.as_slice(),
                                                        )
                                                    })
                                                    .unwrap_or_else(|| uid_str.clone());
                                                (puid, c.name.clone())
                                            })
                                        })
                                        .unwrap_or_else(|| (uid_str.clone(), invoker.name.clone()))
                                } else {
                                    (uid_str.clone(), invoker.name.clone())
                                };
                                let _ = event_tx.send(CoreEventEnvelope {
                                    kind: "chat".to_string(),
                                    chat: Some(ChatMessageDto {
                                        id: next_event_id(&format!("dm_{}", invoker.id.0)),
                                        target: "CLIENT".to_string(),
                                        peer_client_id: Some(i32::from(peer.0)),
                                        peer_uid: Some(peer_uid_resolved),
                                        peer_name: peer_name_resolved,
                                        sender_id: i32::from(invoker.id.0),
                                        sender_name: invoker.name,
                                        sender_uid: uid_str,
                                        content: message,
                                        timestamp_ms: now_ms(),
                                        is_system: false,
                                    }),
                                    ..Default::default()
                                });
                            }
                        }
                    }

                    _ => {}
                }
            }

            if let Some((target_cid, pwd)) = auto_move_target {
                sess.pending_auto_join = None;
                if let Some(con) = sess.con.as_mut() {
                    let mv_pkt = con.get_state().ok().and_then(|st| {
                        st.clients.get(&st.own_client).map(|me| {
                            let mut mv = me.client_move(target_cid);
                            if !pwd.is_empty() {
                                mv = mv.set_password(&pwd);
                            }
                            mv.to_packet()
                        })
                    });
                    if let Some(pkt) = mv_pkt {
                        let _ = pkt.send(con);
                    }
                }
            }

            if let Some(cue_str) = room_cue {
                let _ = event_tx.send(CoreEventEnvelope {
                    kind: "room_cue".to_string(),
                    cue: Some(cue_str.to_string()),
                    ..Default::default()
                });
            }

            emit_snapshot(sess, event_tx, audio_engine);
        }

        StreamItem::MessageEvent(InMessage::PermList(perm_list)) => {
            for item in perm_list.iter() {
                if let (Some(pid), Some(pname)) = (item.permission_id, &item.permission_name) {
                    sess.perm_names.insert(pid.0, pname.clone());
                }
            }
        }

        StreamItem::Audio(packet) => {
            let from_id = match packet.data().data() {
                AudioData::S2C { from, .. } | AudioData::S2CWhisper { from, .. } => {
                    Some(ClientId(*from))
                }
                _ => None,
            };
            if let Some(cid) = from_id {
                if let Ok(mut eng) = audio_engine.lock() {
                    eng.push_incoming_packet(cid, packet);
                }
            }
        }

        StreamItem::IdentityLevelIncreasing(needed) => {
            emit_state(
                event_tx,
                "CONNECTING",
                Some(if is_en() {
                    format!("Upgrading Identity Security Level to Lv.{needed} (multi-core)...")
                } else {
                    format!("正在多核加速提升身份安全等级至 Lv.{needed}...")
                }),
            );
            // Close the temporary handshake connection cleanly and run PoW asynchronously on a
            // blocking worker pool so `run_session_actor` stays 100% responsive to Cancel / Disconnect!
            if let Some(old_con) = sess.con.take() {
                tokio::spawn(gracefully_close_connection(
                    old_con,
                    "Upgrading identity".to_string(),
                ));
            }
            if let Some(prev_cancel) = sess.upgrade_cancel.take() {
                prev_cancel.store(true, Ordering::SeqCst);
            }
            global_security_cancel.store(false, Ordering::SeqCst);
            let session_cancel = Arc::new(AtomicBool::new(false));
            sess.upgrade_cancel = Some(Arc::clone(&session_cancel));

            let raw_ini = sess.identity_ini.clone();
            let sid = sess.session_id;
            let tx_cmd = cmd_tx.clone();
            let tx_ev = event_tx.clone();
            let global_cancel_cl = Arc::clone(global_security_cancel);

            tokio::task::spawn_blocking(move || {
                let cancel_for_cb = Arc::clone(&session_cancel);
                let result = crate::identity::upgrade_security_level_parallel(
                    &raw_ini,
                    needed,
                    session_cancel,
                    move |lvl, checked| {
                        if global_cancel_cl.load(Ordering::Relaxed) {
                            cancel_for_cb.store(true, Ordering::SeqCst);
                            return;
                        }
                        let _ = tx_ev.send(CoreEventEnvelope {
                            kind: "security_progress".to_string(),
                            security_level: Some(i32::from(lvl)),
                            key_offset: Some(checked as i64),
                            ..Default::default()
                        });
                    },
                );
                let _ = tx_cmd.send(SessionCommand::InternalAutoUpgradeFinished {
                    session_id: sid,
                    needed,
                    result,
                });
            });
        }

        StreamItem::IdentityLevelIncreased => {}

        StreamItem::DisconnectedTemporarily(_) => {
            emit_state(
                event_tx,
                "RECONNECTING",
                Some(if is_en() {
                    "Network jitter detected, reconnecting automatically...".to_string()
                } else {
                    "网络波动，正在自动重连语音频道...".to_string()
                }),
            );
        }

        StreamItem::MessageResult(handle, res) => {
            let meta = sess.pending_commands.remove(&handle).unwrap_or_else(|| {
                PendingCmdMeta::simple(if is_en() { "Action" } else { "操作" }, None, true)
            });

            match res {
                Ok(()) => {
                    if let Some(echo) = meta.echo_chat {
                        let _ = event_tx.send(CoreEventEnvelope {
                            kind: "chat".to_string(),
                            chat: Some(echo),
                            ..Default::default()
                        });
                    }
                    if !meta.silent_on_ok {
                        let _ = event_tx.send(CoreEventEnvelope {
                            kind: "command_result".to_string(),
                            action: Some(meta.action.clone()),
                            command_ok: Some(true),
                            reason: Some(if is_en() {
                                format!("{} succeeded", meta.action)
                            } else {
                                format!("{}成功", meta.action)
                            }),
                            target_channel_id: meta.target_channel_id,
                            ..Default::default()
                        });
                    }
                }
                Err(cmd_err) => {
                    if meta.is_create_channel {
                        sess.pending_auto_join = None;
                    }
                    let (msg, code, perm_str, is_perm) =
                        format_command_error(&meta.action, &cmd_err, &sess.perm_names);
                    let _ = event_tx.send(CoreEventEnvelope {
                        kind: "command_result".to_string(),
                        action: Some(meta.action),
                        command_ok: Some(false),
                        reason: Some(msg),
                        error_code: Some(code),
                        missing_perm: perm_str,
                        is_permission_error: Some(is_perm),
                        target_channel_id: meta.target_channel_id,
                        ..Default::default()
                    });
                }
            }
        }

        StreamItem::FileDownload(ft_handle, dl) => {
            if let Some((file_key, dest_path)) = sess.pending_downloads.remove(&ft_handle) {
                if dl.size == 0 || dl.size > MAX_ICON_DOWNLOAD_BYTES {
                    return;
                }
                let capped_size = dl.size.min(MAX_ICON_DOWNLOAD_BYTES);
                let tx = event_tx.clone();
                tokio::spawn(async move {
                    let mut limited = dl.stream.take(capped_size);
                    let mut bytes = Vec::with_capacity(capped_size.min(512 * 1024) as usize);
                    if limited.read_to_end(&mut bytes).await.is_ok() && !bytes.is_empty() {
                        if tokio::fs::write(&dest_path, &bytes).await.is_ok() {
                            let _ = tx.send(CoreEventEnvelope {
                                kind: "file_downloaded".to_string(),
                                file_key: Some(file_key),
                                local_path: Some(dest_path),
                                ..Default::default()
                            });
                        }
                    }
                });
            }
        }

        StreamItem::FiletransferFailed(ft_handle, _) => {
            sess.pending_downloads.remove(&ft_handle);
        }

        StreamItem::AudioChange(_) => {
            if let Some(con) = sess.con.as_ref() {
                if let Ok(mut eng) = audio_engine.lock() {
                    eng.set_server_can_send_audio(!sess.local_mute_blocked && con.can_send_audio());
                }
            }
        }

        StreamItem::RawCommand(raw) => {
            let parsed = sess.stream_tracker.handle_raw_command(&raw);
            if let Some(con) = sess.con.as_mut() {
                for clid in parsed.request_info_clids {
                    let pkt = build_request_stream_info_cmd(clid);
                    let _ = con.send_raw_command(pkt);
                }
            }
            for sig in parsed.signals {
                let _ = event_tx.send(CoreEventEnvelope {
                    kind: "ts6_stream".to_string(),
                    stream_signal: Some(sig),
                    ..Default::default()
                });
            }
            if parsed.snapshot_dirty && sess.connected_emitted {
                emit_snapshot(sess, event_tx, audio_engine);
            }
        }

        _ => {}
    }
}

pub fn translate_ts_permission(perm_name: &str) -> &'static str {
    match perm_name {
        "b_channel_create_permanent" => "创建永久频道权限",
        "b_channel_create_semi_permanent" => "创建半永久频道权限",
        "b_channel_create_temporary" => "创建临时频道权限",
        "b_channel_create_child" => "创建子频道权限",
        "b_channel_create_with_password" => "创建加密频道权限",
        "b_channel_create_with_topic" => "设置频道主题权限",
        "b_channel_create_with_description" => "设置频道简介权限",
        "b_channel_create_with_maxclients" => "设置人数上限权限",
        "b_channel_create_with_needed_talk_power" => "设置发言权门槛权限",
        "b_channel_create_with_default" => "设为默认频道权限",
        "b_channel_create_modify_with_codec_opusvoice" => "使用 Opus 人声编码权限",
        "b_channel_create_modify_with_codec_opusmusic" => "使用 Opus 音乐编码权限",
        "i_channel_create_modify_with_codec_maxquality" => "高音质等级权限",
        "i_channel_join_power" | "i_channel_needed_join_power" => "进入该频道所需权限等级",
        "b_channel_join_permanent" => "进入永久频道权限",
        "b_channel_join_semi_permanent" => "进入半永久频道权限",
        "b_channel_join_temporary" => "进入临时频道权限",
        "b_channel_join_ignore_password" => "免密进入频道权限",
        "b_channel_join_ignore_maxclients" => "满员强行进入权限",
        "i_channel_modify_power" | "i_channel_needed_modify_power" => "修改频道属性权限",
        "b_channel_modify_parent" => "移动父频道层级权限",
        "b_channel_modify_name" => "修改频道名称权限",
        "b_channel_modify_password" => "修改频道密码权限",
        "b_channel_modify_topic" => "修改频道主题权限",
        "b_channel_modify_description" => "修改频道简介权限",
        "b_channel_modify_codec" => "修改音频编码权限",
        "b_channel_modify_codec_quality" => "修改音质等级权限",
        "b_channel_modify_maxclients" => "修改人数上限权限",
        "b_channel_modify_needed_talk_power" => "修改发言权门槛权限",
        "b_channel_modify_make_default" => "设为默认大厅权限",
        "b_channel_modify_make_permanent" => "改为永久频道权限",
        "b_channel_modify_make_semi_permanent" => "改为半永久频道权限",
        "b_channel_modify_make_temporary" => "改为临时频道权限",
        "b_channel_delete_permanent" => "删除永久频道权限",
        "b_channel_delete_semi_permanent" => "删除半永久频道权限",
        "b_channel_delete_temporary" => "删除临时频道权限",
        "b_channel_delete_flag_force" => "强制删除非空频道权限",
        "i_channel_delete_power" | "i_channel_needed_delete_power" => "删除频道所需权限等级",
        "i_channel_description_view_power" => "查看频道简介权限",
        "i_channel_subscribe_power" => "订阅查看频道成员权限",
        "i_client_move_power" | "i_client_needed_move_power" => "移动其他成员权限",
        "i_client_kick_from_server_power" | "i_client_needed_kick_from_server_power" => "踢出服务器权限",
        "i_client_kick_from_channel_power" | "i_client_needed_kick_from_channel_power" => "踢出频道权限",
        "i_client_ban_power" | "i_client_needed_ban_power" => "封禁成员权限",
        "i_client_poke_power" | "i_client_needed_poke_power" => "发送戳一戳权限",
        "i_client_private_textmessage_power" => "发送私聊消息权限",
        "b_client_channel_textmessage_send" => "在当前频道发文字消息权限",
        "b_client_server_textmessage_send" => "在全服频道发文字消息权限",
        "b_virtualserver_token_use" => "使用权限密钥权限",
        "i_client_talk_power" | "i_client_needed_talk_power" => "频道发言权等级",
        "b_client_request_talker" => "申请发言权权限",
        "b_client_set_flag_talker" => "授予成员发言权权限",
        "i_client_whisper_power" | "i_client_needed_whisper_power" => "密语/定向推流权限",
        "b_client_ignore_antiflood" => "免防刷屏限制权限",
        "b_client_force_push_to_talk" => "强制按键说话限制",
        "b_client_use_video" => "开启视频/屏幕共享直播权限",
        "i_video_max_kbps" => "视频直播码率上限权限",
        "i_video_max_streams" => "并发视频直播路数权限",
        _ => "服务器管理/操作权限",
    }
}

fn builtin_ts_perm_name(perm_id: u32) -> Option<&'static str> {
    match perm_id {
        186 => Some("b_client_force_push_to_talk"),
        188 => Some("b_client_ignore_antiflood"),
        190 => Some("b_client_use_channel_commander"),
        191 => Some("b_client_request_talker"),
        221 => Some("i_client_private_textmessage_power"),
        222 => Some("i_client_needed_private_textmessage_power"),
        223 => Some("b_client_server_textmessage_send"),
        224 => Some("b_client_channel_textmessage_send"),
        226 => Some("i_client_talk_power"),
        227 => Some("i_client_needed_talk_power"),
        228 => Some("i_client_poke_power"),
        229 => Some("i_client_needed_poke_power"),
        230 => Some("b_client_set_flag_talker"),
        231 => Some("i_client_whisper_power"),
        232 => Some("i_client_needed_whisper_power"),
        _ => None,
    }
}

pub fn format_command_error(
    action_desc: &str,
    cmd_err: &CommandError,
    perm_names: &HashMap<u32, String>,
) -> (String, u16, Option<String>, bool) {
    let code = cmd_err.error as u16;
    let perm_name_opt = cmd_err.missing_permission.and_then(|p| {
        perm_names
            .get(&p.0)
            .cloned()
            .or_else(|| builtin_ts_perm_name(p.0).map(str::to_string))
    });

    let is_perm = matches!(
        cmd_err.error,
        TsError::PermissionsClientInsufficient
            | TsError::PermissionsInsufficientGroupPower
            | TsError::PermissionsInsufficientPermissionPower
            | TsError::PermissionDefaultGroupForbidden
            | TsError::Permissions
    ) || cmd_err.missing_permission.is_some();

    if is_perm {
        if let Some(ref pname) = perm_name_opt {
            if is_en() {
                return (
                    format!("{action_desc} denied: insufficient permission (missing `{pname}`)"),
                    code,
                    Some(pname.clone()),
                    true,
                );
            }
            let zh = translate_ts_permission(pname);
            return (
                format!("{action_desc}被拒绝：权限不足（缺少「{zh}」{pname}）"),
                code,
                Some(pname.clone()),
                true,
            );
        }
        return (
            if is_en() {
                format!("{action_desc} denied: insufficient permissions. Redeem a Privilege Key if you are an admin.")
            } else {
                format!("{action_desc}被拒绝：当前身份权限不足，若有管理员密钥请先激活")
            },
            code,
            None,
            true,
        );
    }

    let detail = translate_ts_error(cmd_err.error);
    (
        if is_en() {
            format!("{action_desc} failed: {detail}")
        } else {
            format!("{action_desc}失败：{detail}")
        },
        code,
        perm_name_opt,
        false,
    )
}

pub fn translate_ts_error(err: TsError) -> String {
    if is_en() {
        return match err {
            TsError::ChannelInvalidPassword => "Incorrect channel password".to_string(),
            TsError::ServerInvalidPassword => "Incorrect server password".to_string(),
            TsError::ChannelMaxclientsReached => "Target channel is full".to_string(),
            TsError::ChannelMaxfamilyReached => "Channel family client limit reached".to_string(),
            TsError::ServerMaxclientsReached | TsError::AccountingSlotLimitReached => {
                "Server is full".to_string()
            }
            TsError::ChannelAlreadyIn => "You are already in this channel".to_string(),
            TsError::ChannelNameInuse => "Channel name is already in use".to_string(),
            TsError::ChannelNotEmpty => "Channel is not empty; use force delete".to_string(),
            TsError::ChannelCanNotDeleteDefault => "Default lobby channel cannot be deleted".to_string(),
            TsError::ChannelDefaultRequirePermanent => "Default channel must be permanent".to_string(),
            TsError::ChannelParentNotPermanent => "Cannot create permanent sub-channel under temporary parent".to_string(),
            TsError::ChannelIsPrivateChannel => "Private restricted channel".to_string(),
            TsError::ChannelInvalidId => "Channel no longer exists".to_string(),
            TsError::ClientInvalidId => "Target member went offline".to_string(),
            TsError::ClientNicknameInuse => "Nickname is already in use on this server".to_string(),
            TsError::ClientTooManyClonesConnected => "Maximum simultaneous connections for this identity reached".to_string(),
            TsError::ClientIsFlooding | TsError::BanFlooding => "Anti-flood protection triggered; please wait a moment".to_string(),
            TsError::ConnectFailedBanned => "Connection refused: identity or IP is banned".to_string(),
            TsError::RenameFailedBanned => "Name contains prohibited words".to_string(),
            TsError::PrivilegeKeyInvalid | TsError::DatabaseEmptyResult => "Invalid or already redeemed Privilege Key".to_string(),
            TsError::ServerIsShuttingDown | TsError::ServerModalQuit => "Server is shutting down or restarting".to_string(),
            TsError::CommandNotFound => "Unsupported by this server version (Live Streaming requires TeamSpeak 6 server)".to_string(),
            TsError::PermissionsClientInsufficient | TsError::Permissions => "Insufficient permissions".to_string(),
            other if (other as u16) == 0x0606 => "Invalid or incompatible command parameters".to_string(),
            other => format!("{other}"),
        };
    }
    match err {
        TsError::ChannelInvalidPassword => "频道密码不正确，请重新输入".to_string(),
        TsError::ServerInvalidPassword => "服务器连接密码错误".to_string(),
        TsError::ChannelMaxclientsReached => "目标频道人数已满".to_string(),
        TsError::ChannelMaxfamilyReached => "目标频道及子频道总人数已达上限".to_string(),
        TsError::ServerMaxclientsReached | TsError::AccountingSlotLimitReached => {
            "服务器在线人数已满".to_string()
        }
        TsError::ChannelAlreadyIn => "你已经在该频道中".to_string(),
        TsError::ChannelNameInuse => "该频道名称已被占用，请换一个名称".to_string(),
        TsError::ChannelNotEmpty => "频道内仍有成员或子频道，请使用强制删除".to_string(),
        TsError::ChannelCanNotDeleteDefault => "默认大厅频道不允许被删除".to_string(),
        TsError::ChannelDefaultRequirePermanent => "默认大厅频道必须设为永久频道".to_string(),
        TsError::ChannelParentNotPermanent => {
            "非永久父频道下不能创建永久子频道".to_string()
        }
        TsError::ChannelIsPrivateChannel => "该频道为私有受限频道".to_string(),
        TsError::ChannelInvalidId => "目标频道不存在或已被删除".to_string(),
        TsError::ClientInvalidId => "目标成员已离线".to_string(),
        TsError::ClientNicknameInuse => "当前昵称已被服务器内其他成员占用".to_string(),
        TsError::ClientTooManyClonesConnected => {
            "同一身份同时在线设备数已达服务器上限".to_string()
        }
        TsError::ClientIsFlooding | TsError::BanFlooding => {
            "操作过于频繁触发防刷屏保护，请稍后再试".to_string()
        }
        TsError::ConnectFailedBanned => "连接被拒绝：当前身份或 IP 处于封禁状态".to_string(),
        TsError::RenameFailedBanned => "名称包含服务器违禁词".to_string(),
        TsError::PrivilegeKeyInvalid | TsError::DatabaseEmptyResult => {
            "权限密钥无效或已被使用".to_string()
        }
        TsError::ServerIsShuttingDown | TsError::ServerModalQuit => {
            "服务器已关闭或正在维护重启".to_string()
        }
        TsError::CommandNotFound => {
            "当前服务器版本不支持此功能（直播功能需 TeamSpeak 6 服务端支持）".to_string()
        }
        TsError::PermissionsClientInsufficient | TsError::Permissions => {
            "当前身份权限不足".to_string()
        }
        other if (other as u16) == 0x0606 => "请求参数缺失或不兼容".to_string(),
        other => format!("{other}"),
    }
}

fn format_connect_error(err: &tsclientlib::Error) -> String {
    if is_en() {
        return match err {
            tsclientlib::Error::ConnectTs(ts_err) => {
                format!("Connection refused: {}", translate_ts_error(*ts_err))
            }
            tsclientlib::Error::IdentityLevel(needed) => format!(
                "Server requires Security Level ≥ Lv.{needed}. Upgrade in Settings → Identity Keys."
            ),
            tsclientlib::Error::InitserverTimeout => {
                "Connection handshake timed out. Check server host/port and UDP firewall.".to_string()
            }
            other => format!("Connection error: {other}"),
        };
    }
    match err {
        tsclientlib::Error::ConnectTs(ts_err) => {
            format!("服务器拒绝连接：{}", translate_ts_error(*ts_err))
        }
        tsclientlib::Error::IdentityLevel(needed) => format!(
            "服务器要求安全等级至少 Lv.{needed}，请前往「设置 → 身份密钥」提升等级"
        ),
        tsclientlib::Error::InitserverTimeout => {
            "连接握手超时，请检查服务器地址/端口是否正确或防火墙 UDP 通畅".to_string()
        }
        other => format!("服务器连接异常：{other}"),
    }
}

fn emit_snapshot(
    sess: &ActiveSession,
    event_tx: &mpsc::UnboundedSender<CoreEventEnvelope>,
    audio_engine: &Arc<Mutex<RealtimeAudioEngine>>,
) {
    let Some(con) = sess.con.as_ref() else { return };
    let Ok(book) = con.get_state() else { return };

    let loss_pct = con
        .get_network_stats()
        .map(|st| st.get_packetloss_s2c_total())
        .unwrap_or(0.0);
    let ping_ms = con
        .get_wire_rtt()
        .map(|d| (d.as_millis() as i32).max(1))
        .unwrap_or(0);

    let mut uid_map = HashMap::new();
    let clients: Vec<OnlineClientDto> = book
        .clients
        .values()
        .map(|c| {
            let uid_str = c
                .uid
                .as_ref()
                .map(|u| {
                    base64::Engine::encode(
                        &base64::engine::general_purpose::STANDARD,
                        u.0.as_slice(),
                    )
                })
                .unwrap_or_default();
            if !uid_str.is_empty() {
                uid_map.insert(c.id, uid_str.clone());
            }
            let st_meta = sess.stream_tracker.streams_by_clid.get(&c.id.0);
            let is_streaming =
                st_meta.is_some() || sess.stream_tracker.streaming_clids.contains(&c.id.0);
            OnlineClientDto {
                id: i32::from(c.id.0),
                channel_id: c.channel.0 as i64,
                uid: uid_str,
                nickname: c.name.clone(),
                is_talking: false,
                input_muted: c.input_muted,
                output_muted: c.output_muted || c.output_only_muted,
                input_hardware: c.input_hardware_enabled,
                output_hardware: c.output_hardware_enabled,
                is_away: c.away_message.is_some(),
                away_message: c.away_message.clone().unwrap_or_default(),
                is_channel_commander: c.is_channel_commander,
                talk_power: c.talk_power,
                talk_power_granted: c.talk_power_granted,
                server_groups: c.server_groups.iter().map(|g| g.0 as i64).collect(),
                channel_group: c.channel_group.0 as i64,
                icon_id: i64::from(c.icon.0),
                avatar_flag: c.avatar_hash.clone(),
                client_type: match c.client_type {
                    ClientType::Normal => 0,
                    ClientType::Query { .. } => 1,
pub fn translate_ts_permission_en(perm_name: &str) -> &'static str {
    match perm_name {
        "b_channel_create_permanent" => "Create Permanent Channel",
        "b_channel_create_semi_permanent" => "Create Semi-Permanent Channel",
        "b_channel_create_temporary" => "Create Temporary Channel",
        "b_channel_create_child" => "Create Sub-channel",
        "b_channel_create_with_password" => "Create Password-Protected Channel",
        "b_channel_create_with_topic" => "Set Channel Topic",
        "b_channel_create_with_description" => "Set Channel Description",
        "b_channel_create_with_maxclients" => "Set Max Clients",
        "b_channel_create_with_needed_talk_power" => "Set Needed Talk Power",
        "b_channel_create_with_default" => "Set Default Channel",
        "b_channel_create_modify_with_codec_opusvoice" => "Use Opus Voice Codec",
        "b_channel_create_modify_with_codec_opusmusic" => "Use Opus Music Codec",
        "i_channel_create_modify_with_codec_maxquality" => "Max Codec Quality",
        "i_channel_join_power" | "i_channel_needed_join_power" => "Channel Join Power",
        "b_channel_join_permanent" => "Join Permanent Channel",
        "b_channel_join_semi_permanent" => "Join Semi-Permanent Channel",
        "b_channel_join_temporary" => "Join Temporary Channel",
        "b_channel_join_ignore_password" => "Ignore Channel Password",
        "b_channel_join_ignore_maxclients" => "Ignore Channel Max Clients",
        "i_channel_modify_power" | "i_channel_needed_modify_power" => "Channel Modify Power",
        "b_channel_modify_parent" => "Move Channel Parent",
        "b_channel_modify_name" => "Modify Channel Name",
        "b_channel_modify_password" => "Modify Channel Password",
        "b_channel_modify_topic" => "Modify Channel Topic",
        "b_channel_modify_description" => "Modify Channel Description",
        "b_channel_modify_codec" => "Modify Channel Codec",
        "b_channel_modify_codec_quality" => "Modify Codec Quality",
        "b_channel_modify_maxclients" => "Modify Max Clients",
        "b_channel_modify_needed_talk_power" => "Modify Needed Talk Power",
        "b_channel_modify_make_default" => "Make Default Lobby",
        "b_channel_modify_make_permanent" => "Make Permanent Channel",
        "b_channel_modify_make_semi_permanent" => "Make Semi-Permanent Channel",
        "b_channel_modify_make_temporary" => "Make Temporary Channel",
        "b_channel_delete_permanent" => "Delete Permanent Channel",
        "b_channel_delete_semi_permanent" => "Delete Semi-Permanent Channel",
        "b_channel_delete_temporary" => "Delete Temporary Channel",
        "b_channel_delete_flag_force" => "Force Delete Occupied Channel",
        "i_channel_delete_power" | "i_channel_needed_delete_power" => "Channel Delete Power",
        "i_channel_description_view_power" => "View Channel Description",
        "i_channel_subscribe_power" => "Subscribe Channel Members",
        "i_client_move_power" | "i_client_needed_move_power" => "Move Member Power",
        "i_client_kick_from_server_power" | "i_client_needed_kick_from_server_power" => "Kick from Server Power",
        "i_client_kick_from_channel_power" | "i_client_needed_kick_from_channel_power" => "Kick from Channel Power",
        "i_client_ban_power" | "i_client_needed_ban_power" => "Ban Member Power",
        "i_client_poke_power" | "i_client_needed_poke_power" => "Send Poke Power",
        "i_client_private_textmessage_power" => "Private Message Power",
        "b_client_channel_textmessage_send" => "Send Channel Text Message",
        "b_client_server_textmessage_send" => "Send Server Text Message",
        "b_virtualserver_token_use" => "Redeem Privilege Key",
        "i_client_talk_power" | "i_client_needed_talk_power" => "Channel Talk Power",
        "b_client_request_talker" => "Request Talk Power",
        "b_client_set_flag_talker" => "Grant Member Talk Power",
        "i_client_whisper_power" | "i_client_needed_whisper_power" => "Whisper / Stream Power",
        "b_client_ignore_antiflood" => "Ignore Anti-Flood",
        "b_client_force_push_to_talk" => "Forced Push-To-Talk",
        "b_client_use_video" => "Start Video / Screen Share",
        "i_video_max_kbps" => "Max Video Bitrate",
        "i_video_max_streams" => "Max Concurrent Video Streams",
        "i_group_member_add_power" | "i_group_needed_member_add_power" => "Assign Group Power",
        "i_group_member_remove_power" | "i_group_needed_member_remove_power" => "Remove Group Power",
        "b_client_modify_description" => "Modify Member Description",
        "b_client_modify_own_description" => "Modify Own Description",
        "b_client_use_channel_commander" => "Use Channel Commander",
        _ => "Server Moderation Permission",
    }
}

                },
                is_streaming,
                stream_id: st_meta.map(|m| m.stream_id.clone()).unwrap_or_default(),
                stream_name: st_meta.map(|m| m.name.clone()).unwrap_or_default(),
                stream_type: st_meta
                    .map(|m| m.stream_type)
                    .unwrap_or(if is_streaming { STREAM_TYPE_SCREEN } else { 0 }),
                stream_audio: st_meta.map(|m| m.audio).unwrap_or(false),
                stream_bitrate: st_meta.map(|m| m.bitrate as i32).unwrap_or(0),
                stream_viewers: st_meta.map(|m| m.viewer_count as i32).unwrap_or(0),
            }
        })
        .collect();

    let channels: Vec<ChannelNodeDto> = book
        .channels
        .values()
        .map(|ch| map_channel(ch))
        .collect();

    let my_channel_id = book
        .clients
        .get(&book.own_client)
        .map(|c| c.channel.0 as i64)
        .unwrap_or(0);

    let (my_codec, my_quality) = channels
        .iter()
        .find(|ch| ch.id == my_channel_id)
        .map(|ch| (ch.codec, ch.codec_quality.clamp(1, 10) as u8))
        .unwrap_or((4, 6));

    if let Ok(mut eng) = audio_engine.lock() {
        eng.set_server_can_send_audio(!sess.local_mute_blocked && con.can_send_audio());
        eng.set_channel_audio_format(my_codec, my_quality);
        eng.update_client_uid_map(uid_map);
    }

    let snap = ServerSnapshotDto {
        session_id: sess.session_id,
        server_name: book.server.name.clone(),
        server_address: sess.display_address.clone(),
        welcome_message: book.server.welcome_message.clone(),
        host_message: book.server.hostmessage.clone(),
        platform: book.server.platform.clone(),
        version: book.server.version.clone(),
                let en_desc = translate_ts_permission_en(pname);
        max_clients: i32::from(book.server.max_clients),
        my_client_id: i32::from(book.own_client.0),
        my_channel_id,
        server_icon_id: i64::from(book.server.icon.0),
        ping_ms,
        packet_loss_pct: loss_pct,
        channels,
        clients,
    };

    let _ = event_tx.send(CoreEventEnvelope {
        kind: "snapshot".to_string(),
        snapshot: Some(snap),
        ..Default::default()
    });
}

fn map_channel(ch: &data::Channel) -> ChannelNodeDto {
    let max_clients = match ch.max_clients {
        Some(MaxClients::Limited(n)) => i32::from(n),
        _ => -1,
    };
    let codec_num = match ch.codec {
        Codec::SpeexNarrowband => 0,
        Codec::SpeexWideband => 1,
        Codec::SpeexUltrawideband => 2,
        Codec::CeltMono => 3,
        Codec::OpusVoice => 4,
        Codec::OpusMusic => 5,
    };
    ChannelNodeDto {
        id: ch.id.0 as i64,
        parent_id: ch.parent.0 as i64,
        order: ch.order.0 as i64,
        name: ch.name.clone(),
        topic: ch.topic.clone().unwrap_or_default(),
        description: ch
            .optional_data
            .as_ref()
            .map(|d| d.description.clone())
            .unwrap_or_default(),
        has_password: ch.has_password.unwrap_or(false),
        is_permanent: ch.channel_type == ChannelType::Permanent,
        is_semi_permanent: ch.channel_type == ChannelType::SemiPermanent,
        is_default: ch.is_default.unwrap_or(false),
        max_clients,
        needed_talk_power: ch.needed_talk_power.unwrap_or(0),
        codec: codec_num,
        codec_quality: i32::from(ch.codec_quality.unwrap_or(6)),
        icon_id: ch.icon.map(|ic| i64::from(ic.0)).unwrap_or(0),
    }
}

fn emit_state(
    event_tx: &mpsc::UnboundedSender<CoreEventEnvelope>,
    status: &str,
    reason: Option<String>,
) {
    let _ = event_tx.send(CoreEventEnvelope {
        kind: "connection_state".to_string(),
        status: Some(status.to_string()),
        reason,
        ..Default::default()
    });
}

fn now_ms() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as i64)
        .unwrap_or(0)
}

#[cfg(test)]
mod tests {
    use super::*;
    use tsclientlib::Permission;

    #[test]
    fn formats_missing_permission_with_readable_chinese_name() {
        let mut perms = HashMap::new();
        perms.insert(42u32, "b_channel_create_permanent".to_string());
        let err = CommandError {
            error: TsError::PermissionsClientInsufficient,
            missing_permission: Some(Permission(42)),
        };
        let (msg, code, perm_str, is_perm) = format_command_error("创建频道", &err, &perms);
        assert!(is_perm);
        assert_eq!(code, TsError::PermissionsClientInsufficient as u16);
        assert_eq!(perm_str.as_deref(), Some("b_channel_create_permanent"));
        assert!(msg.contains("创建永久频道权限"));
    }

    #[test]
    fn translates_channel_invalid_password_code() {
        let perms = HashMap::new();
        let err = CommandError {
            error: TsError::ChannelInvalidPassword,
            missing_permission: None,
        };
        let (msg, code, _, is_perm) = format_command_error("切换频道", &err, &perms);
        assert!(!is_perm);
        assert_eq!(code, 0x030d);
        assert!(msg.contains("密码不正确"));
    }
}

    #[test]
    fn disconnect_sync_acknowledges_completion_promptly() {
        let rt = tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()
            .expect("tokio runtime");
        let (cmd_tx, cmd_rx) = mpsc::unbounded_channel();
        let (ev_tx, _ev_rx) = mpsc::unbounded_channel();
        let audio = Arc::new(Mutex::new(RealtimeAudioEngine::new().expect("audio engine")));
        let cancel = Arc::new(AtomicBool::new(false));
        let actor_cmd_tx = cmd_tx.clone();
        rt.spawn(async move {
            run_session_actor(cmd_rx, actor_cmd_tx, ev_tx, audio, cancel).await;
        });

        let (done_tx, done_rx) = std::sync::mpsc::sync_channel(1);
        cmd_tx
            .send(SessionCommand::DisconnectSync {
                reason: "Task removed".to_string(),
                timeout_ms: 250,
                done_tx,
            })
            .expect("send DisconnectSync");

        rt.block_on(async {
            tokio::task::yield_now().await;
        });
        assert!(done_rx.recv_timeout(Duration::from_millis(200)).is_ok());
    }
