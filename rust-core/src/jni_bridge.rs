use std::sync::atomic::{AtomicBool, AtomicI64, Ordering};
use std::sync::{Arc, Mutex, OnceLock};

use jni::objects::{GlobalRef, JByteBuffer, JClass, JObject, JString, JValue};
use jni::sys::{jboolean, jfloat, jint, jlong, jstring, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;
use tokio::runtime::Runtime;
use tokio::sync::mpsc;

use crate::audio_engine::{CaptureVoiceMode, RealtimeAudioEngine, MONO_FRAME_SAMPLES};
use crate::events::{CoreEventEnvelope, IS_ENGLISH};
use crate::identity::{generate_identity, inspect_identity_ini, upgrade_security_level_parallel};
use crate::session::{run_session_actor, SessionCommand};

struct DirectPcmBuffers {
    _capture_ref: GlobalRef,
    _playback_ref: GlobalRef,
    capture_ptr: *mut i16,
    playback_ptr: *mut i16,
}

unsafe impl Send for DirectPcmBuffers {}
unsafe impl Sync for DirectPcmBuffers {}

struct EngineContext {
    _runtime: Runtime,
    cmd_tx: mpsc::UnboundedSender<SessionCommand>,
    event_tx: mpsc::UnboundedSender<CoreEventEnvelope>,
    audio_engine: Arc<Mutex<RealtimeAudioEngine>>,
    pcm_buffers: Mutex<Option<DirectPcmBuffers>>,
    security_cancel: Arc<AtomicBool>,
    next_session_id: AtomicI64,
}

static ENGINE: OnceLock<EngineContext> = OnceLock::new();

fn init_android_logging() {
    #[cfg(target_os = "android")]
    {
        android_logger::init_once(
            android_logger::Config::default()
                .with_max_level(log::LevelFilter::Info)
                .with_tag("tscc_core"),
        );
    }
}

fn jstr(env: &mut JNIEnv, input: &JString) -> String {
    env.get_string(input)
        .map(|s| s.into())
        .unwrap_or_default()
}

fn to_jstring(env: &mut JNIEnv, text: &str) -> jstring {
    env.new_string(text)
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

fn json_err(msg: &str) -> String {
    serde_json::json!({ "error": msg }).to_string()
}

#[no_mangle]
pub extern "system" fn Java_com_cloudwhile_tscc_data_NativeBridge_nativeInit(
    env: JNIEnv,
    _class: JClass,
    callback_target: JObject,
) {
    init_android_logging();
    if ENGINE.get().is_some() {
        return;
    }

    let Ok(vm) = env.get_java_vm() else { return };
    let Ok(cb_global) = env.new_global_ref(callback_target) else { return };

    let Ok(runtime) = tokio::runtime::Builder::new_multi_thread()
        .worker_threads(2)
        .enable_all()
        .build()
    else {
        return;
    };

    let Ok(audio) = RealtimeAudioEngine::new() else { return };
    let audio_arc = Arc::new(Mutex::new(audio));

    let (cmd_tx, cmd_rx) = mpsc::unbounded_channel::<SessionCommand>();
    let (event_tx, mut event_rx) = mpsc::unbounded_channel::<CoreEventEnvelope>();

    let security_cancel = Arc::new(AtomicBool::new(false));
    let actor_cmd_tx = cmd_tx.clone();
    let actor_event_tx = event_tx.clone();
    let actor_audio = Arc::clone(&audio_arc);
    let actor_cancel = Arc::clone(&security_cancel);
    runtime.spawn(async move {
        run_session_actor(cmd_rx, actor_cmd_tx, actor_event_tx, actor_audio, actor_cancel).await;
    });

    // Dedicated JNI callback dispatcher thread attached permanently to JVM
    std::thread::Builder::new()
        .name("tscc-jni-events".to_string())
        .spawn(move || {
            let Ok(mut thread_env) = vm.attach_current_thread_permanently() else {
                return;
            };
            while let Some(ev) = event_rx.blocking_recv() {
                if let Ok(json_str) = serde_json::to_string(&ev) {
                    let _ = thread_env.with_local_frame(8, |frame_env| -> jni::errors::Result<()> {
                        let js = frame_env.new_string(&json_str)?;
                        let _ = frame_env.call_method(
                            &cb_global,
                            "onCoreEventJson",
                            "(Ljava/lang/String;)V",
                            &[JValue::Object(&JObject::from(js))],
                        );
                        if frame_env.exception_check().unwrap_or(false) {
                            let _ = frame_env.exception_clear();
                        }
                        Ok(())
                    });
                }
            }
        })
        .ok();

    let _ = ENGINE.set(EngineContext {
        _runtime: runtime,
        cmd_tx,
        event_tx,
        audio_engine: audio_arc,
        pcm_buffers: Mutex::new(None),
        security_cancel,
        next_session_id: AtomicI64::new(1),
    });
}

#[no_mangle]
pub extern "system" fn Java_com_cloudwhile_tscc_data_NativeBridge_nativeSetLanguage(
    _env: JNIEnv,
    _class: JClass,
    is_english: jboolean,
) {
    IS_ENGLISH.store(is_english == JNI_TRUE, Ordering::Relaxed);
}

#[no_mangle]
pub extern "system" fn Java_com_cloudwhile_tscc_data_NativeBridge_nativeGenerateIdentity(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    let out = match generate_identity() {
        Ok(dto) => serde_json::to_string(&dto).unwrap_or_else(|e| json_err(&e.to_string())),
        Err(e) => json_err(&e),
    };
    to_jstring(&mut env, &out)
}

#[no_mangle]
pub extern "system" fn Java_com_cloudwhile_tscc_data_NativeBridge_nativeInspectIdentityIni(
    mut env: JNIEnv,
    _class: JClass,
    ini_or_key: JString,
) -> jstring {
    let raw = jstr(&mut env, &ini_or_key);
    let out = match inspect_identity_ini(&raw) {
        Ok(dto) => serde_json::to_string(&dto).unwrap_or_else(|e| json_err(&e.to_string())),
        Err(e) => json_err(&e),
    };
    to_jstring(&mut env, &out)
}

#[no_mangle]
pub extern "system" fn Java_com_cloudwhile_tscc_data_NativeBridge_nativeUpgradeSecurityLevel(
    mut env: JNIEnv,
    _class: JClass,
    ini_or_key: JString,
    target_level: jint,
) -> jstring {
    let raw = jstr(&mut env, &ini_or_key);
    let target = (target_level.clamp(8, 40)) as u8;
    let cancel = ENGINE
        .get()
        .map(|ctx| Arc::clone(&ctx.security_cancel))
        .unwrap_or_else(|| Arc::new(AtomicBool::new(false)));
    let ev_tx = ENGINE.get().map(|ctx| ctx.event_tx.clone());

    let out = match upgrade_security_level_parallel(&raw, target, cancel, move |lvl, offset| {
        if let Some(ref tx) = ev_tx {
            let _ = tx.send(CoreEventEnvelope {
                kind: "security_progress".to_string(),
                security_level: Some(i32::from(lvl)),
                key_offset: Some(offset as i64),
                ..Default::default()
            });
        }
    }) {
        Ok(dto) => serde_json::to_string(&dto).unwrap_or_else(|e| json_err(&e.to_string())),
        Err(e) => json_err(&e),
    };
    to_jstring(&mut env, &out)
}

#[no_mangle]
pub extern "system" fn Java_com_cloudwhile_tscc_data_NativeBridge_nativeCancelSecurityUpgrade(
    _env: JNIEnv,
    _class: JClass,
) {
    if let Some(ctx) = ENGINE.get() {
        ctx.security_cancel.store(true, Ordering::SeqCst);
        let _ = ctx.cmd_tx.send(SessionCommand::CancelAutoUpgrade);
    }
}

#[no_mangle]
pub extern "system" fn Java_com_cloudwhile_tscc_data_NativeBridge_nativeConnect(
    mut env: JNIEnv,
    _class: JClass,
    address: JString,
    port: jint,
    nickname: JString,
    identity_ini: JString,
    server_password: JString,
    default_channel: JString,
    channel_password: JString,
    dns_servers_csv: JString,
) -> jboolean {
    let Some(ctx) = ENGINE.get() else {
        return JNI_FALSE;
    };
    let sid = ctx.next_session_id.fetch_add(1, Ordering::Relaxed);
    let ok = ctx
        .cmd_tx
        .send(SessionCommand::Connect {
            session_id: sid,
            address: jstr(&mut env, &address),
            port: (port.clamp(1, 65535)) as u16,
            nickname: jstr(&mut env, &nickname),
            identity_ini: jstr(&mut env, &identity_ini),
            server_password: jstr(&mut env, &server_password),
            default_channel: jstr(&mut env, &default_channel),
            channel_password: jstr(&mut env, &channel_password),
            dns_servers_csv: jstr(&mut env, &dns_servers_csv),
        })
        .is_ok();
    if ok { JNI_TRUE } else { JNI_FALSE }
}

#[no_mangle]
pub extern "system" fn Java_com_cloudwhile_tscc_data_NativeBridge_nativeDisconnect(
    mut env: JNIEnv,
    _class: JClass,
    reason_message: JString,
) {
    if let Some(ctx) = ENGINE.get() {
        let _ = ctx.cmd_tx.send(SessionCommand::Disconnect {
            reason: jstr(&mut env, &reason_message),
        });
    }
}

#[no_mangle]
pub extern "system" fn Java_com_cloudwhile_tscc_data_NativeBridge_nativeDisconnectBlocking(
    mut env: JNIEnv,
    _class: JClass,
    reason_message: JString,
    timeout_ms: jlong,
) {
    let Some(ctx) = ENGINE.get() else { return };
    let reason = jstr(&mut env, &reason_message);
    let capped_ms = (timeout_ms.clamp(50, 1500)) as u64;
    let (done_tx, done_rx) = std::sync::mpsc::sync_channel::<()>(1);
    if ctx
        .cmd_tx
        .send(SessionCommand::DisconnectSync {
            reason,
            timeout_ms: capped_ms,
            done_tx,
        })
        .is_ok()
    {
        let _ = done_rx.recv_timeout(std::time::Duration::from_millis(capped_ms + 40));
    }
}

#[no_mangle]
pub extern "system" fn Java_com_cloudwhile_tscc_data_NativeBridge_nativeSwitchChannel(
    mut env: JNIEnv,
    _class: JClass,
    channel_id: jlong,
    password: JString,
) -> jboolean {
    let Some(ctx) = ENGINE.get() else {
        return JNI_FALSE;
    };
    let ok = ctx
        .cmd_tx
        .send(SessionCommand::SwitchChannel {
            channel_id: channel_id as u64,
            password: jstr(&mut env, &password),
        })
        .is_ok();
    if ok { JNI_TRUE } else { JNI_FALSE }
}

#[no_mangle]
pub extern "system" fn Java_com_cloudwhile_tscc_data_NativeBridge_nativeSendMessage(
    mut env: JNIEnv,
    _class: JClass,
    target_mode: jint,
    target_id: jlong,
    message: JString,
) -> jboolean {
    let Some(ctx) = ENGINE.get() else {
        return JNI_FALSE;
    };
    let ok = ctx
        .cmd_tx
        .send(SessionCommand::SendMessage {
            target_mode,
            target_id: target_id as u64,
            message: jstr(&mut env, &message),
        })
        .is_ok();
    if ok { JNI_TRUE } else { JNI_FALSE }
}

#[no_mangle]
pub extern "system" fn Java_com_cloudwhile_tscc_data_NativeBridge_nativeSetSelfMuteState(
    mut env: JNIEnv,
    _class: JClass,
    input_muted: jboolean,
    output_muted: jboolean,
    away: jboolean,
    away_message: JString,
) {
    if let Some(ctx) = ENGINE.get() {
        let _ = ctx.cmd_tx.send(SessionCommand::SetSelfMute {
            input_muted: input_muted == JNI_TRUE,
            output_muted: output_muted == JNI_TRUE,
            away: away == JNI_TRUE,
            away_message: jstr(&mut env, &away_message),
        });
    }
}

#[no_mangle]
pub extern "system" fn Java_com_cloudwhile_tscc_data_NativeBridge_nativeAttachAudioBuffers(
    env: JNIEnv,
    _class: JClass,
    capture_buf: JByteBuffer,
    playback_buf: JByteBuffer,
) {
    let Some(ctx) = ENGINE.get() else { return };
    let Ok(cap_ptr) = env.get_direct_buffer_address(&capture_buf) else { return };
    let Ok(cap_len) = env.get_direct_buffer_capacity(&capture_buf) else { return };
    let Ok(play_ptr) = env.get_direct_buffer_address(&playback_buf) else { return };
    let Ok(play_len) = env.get_direct_buffer_capacity(&playback_buf) else { return };

    if cap_len < MONO_FRAME_SAMPLES * 2 || play_len < MONO_FRAME_SAMPLES * 2 {
        return;
    }

    let Ok(cap_ref) = env.new_global_ref(JObject::from(capture_buf)) else { return };
    let Ok(play_ref) = env.new_global_ref(JObject::from(playback_buf)) else { return };

    if let Ok(mut guard) = ctx.pcm_buffers.lock() {
        *guard = Some(DirectPcmBuffers {
            _capture_ref: cap_ref,
            _playback_ref: play_ref,
            capture_ptr: cap_ptr as *mut i16,
            playback_ptr: play_ptr as *mut i16,
        });
    }
}

#[no_mangle]
pub extern "system" fn Java_com_cloudwhile_tscc_data_NativeBridge_nativeOnCaptureFrameReady(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    let Some(ctx) = ENGINE.get() else {
        return JNI_FALSE;
    };

    let mut local_pcm = [0i16; MONO_FRAME_SAMPLES];
    {
        let Ok(buf_guard) = ctx.pcm_buffers.lock() else {
            return JNI_FALSE;
        };
        let Some(ref bufs) = *buf_guard else {
            return JNI_FALSE;
        };
        unsafe {
            std::ptr::copy_nonoverlapping(bufs.capture_ptr, local_pcm.as_mut_ptr(), MONO_FRAME_SAMPLES);
        }
    }

    let (outcome, emit_telemetry) = {
        let Ok(mut eng) = ctx.audio_engine.lock() else {
            return JNI_FALSE;
        };
        let out = eng.process_capture_pcm(&local_pcm);
        let emit = eng.should_emit_capture_telemetry(out.input_db);
        (out, emit)
    };

    if let Some(pkt) = outcome.outgoing_packet {
        let _ = ctx.cmd_tx.send(SessionCommand::SendAudio(pkt));
    }

    if emit_telemetry {
        let _ = ctx.event_tx.send(CoreEventEnvelope {
            kind: "voice_telemetry".to_string(),
            input_db: Some(outcome.input_db),
            voice_transmitting: Some(outcome.is_transmitting),
            ..Default::default()
        });
    }

    if outcome.is_transmitting {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

#[no_mangle]
pub extern "system" fn Java_com_cloudwhile_tscc_data_NativeBridge_nativePullPlaybackFrame(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    let Some(ctx) = ENGINE.get() else { return 0 };

    let mut out_pcm = [0i16; MONO_FRAME_SAMPLES];
    let (active_count, changed_speakers) = {
        let Ok(mut eng) = ctx.audio_engine.lock() else { return 0 };
        let (cnt, ids) = eng.pull_playback_pcm(&mut out_pcm);
        let changed = eng.take_changed_speakers(&ids);
        (cnt, changed)
    };

    if active_count > 0 {
        if let Ok(buf_guard) = ctx.pcm_buffers.lock() {
            if let Some(ref bufs) = *buf_guard {
                unsafe {
                    std::ptr::copy_nonoverlapping(
                        out_pcm.as_ptr(),
                        bufs.playback_ptr,
                        MONO_FRAME_SAMPLES,
                    );
                }
            }
        }
    }

    if let Some(speaking_ids) = changed_speakers {
        let _ = ctx.event_tx.send(CoreEventEnvelope {
            kind: "voice_telemetry".to_string(),
            speaking_ids: Some(speaking_ids),
            ..Default::default()
        });
    }

    active_count as jint
}

#[no_mangle]
pub extern "system" fn Java_com_cloudwhile_tscc_data_NativeBridge_nativeConfigureVoice(
    _env: JNIEnv,
    _class: JClass,
    mode: jint,
    vad_threshold_db: jfloat,
    ptt_active: jboolean,
    loopback_enabled: jboolean,
    anti_plosive_enabled: jboolean,
    capture_blocked: jboolean,
) {
    let Some(ctx) = ENGINE.get() else { return };
    if let Ok(mut eng) = ctx.audio_engine.lock() {
        eng.mode = CaptureVoiceMode::from_code(mode);
        eng.vad_threshold_db = vad_threshold_db.clamp(-80.0, -5.0);
        eng.ptt_active = ptt_active == JNI_TRUE;
        eng.loopback_enabled = loopback_enabled == JNI_TRUE;
        eng.anti_plosive_enabled = anti_plosive_enabled == JNI_TRUE;
        eng.set_local_capture_blocked(capture_blocked == JNI_TRUE);
    }
}

#[no_mangle]
pub extern "system" fn Java_com_cloudwhile_tscc_data_NativeBridge_nativeSetUserAudio(
    mut env: JNIEnv,
    _class: JClass,
    client_uid: JString,
    volume_percent: jint,
    muted: jboolean,
) {
    let Some(ctx) = ENGINE.get() else { return };
    let uid = jstr(&mut env, &client_uid);
    if let Ok(mut eng) = ctx.audio_engine.lock() {
        eng.set_user_audio_pref(uid, volume_percent, muted == JNI_TRUE);
    }
}

#[no_mangle]
pub extern "system" fn Java_com_cloudwhile_tscc_data_NativeBridge_nativeSendPoke(
    mut env: JNIEnv,
    _class: JClass,
    client_id: jint,
    message: JString,
) -> jboolean {
    let Some(ctx) = ENGINE.get() else {
        return JNI_FALSE;
    };
    let ok = ctx
        .cmd_tx
        .send(SessionCommand::SendPoke {
            client_id: client_id as u16,
            message: jstr(&mut env, &message),
        })
        .is_ok();
    if ok { JNI_TRUE } else { JNI_FALSE }
}

#[no_mangle]
pub extern "system" fn Java_com_cloudwhile_tscc_data_NativeBridge_nativeUsePrivilegeKey(
    mut env: JNIEnv,
    _class: JClass,
    token: JString,
) -> jboolean {
    let Some(ctx) = ENGINE.get() else {
        return JNI_FALSE;
    };
    let ok = ctx
        .cmd_tx
        .send(SessionCommand::UsePrivilegeKey {
            token: jstr(&mut env, &token),
        })
        .is_ok();
    if ok { JNI_TRUE } else { JNI_FALSE }
}

#[no_mangle]
pub extern "system" fn Java_com_cloudwhile_tscc_data_NativeBridge_nativeRequestTalkPower(
    mut env: JNIEnv,
    _class: JClass,
    request: jboolean,
    message: JString,
) -> jboolean {
    let Some(ctx) = ENGINE.get() else {
        return JNI_FALSE;
    };
    let ok = ctx
        .cmd_tx
        .send(SessionCommand::RequestTalkPower {
            request: request == JNI_TRUE,
            message: jstr(&mut env, &message),
        })
        .is_ok();
    if ok { JNI_TRUE } else { JNI_FALSE }
}

#[no_mangle]
pub extern "system" fn Java_com_cloudwhile_tscc_data_NativeBridge_nativeCreateChannel(
    mut env: JNIEnv,
    _class: JClass,
    name: JString,
    parent_id: jlong,
    password: JString,
    topic: JString,
    description: JString,
    max_clients: jint,
    channel_type: jint,
    is_default: jboolean,
    opus_music: jboolean,
    quality: jint,
    needed_talk_power: jint,
    auto_join: jboolean,
) -> jboolean {
    let Some(ctx) = ENGINE.get() else {
        return JNI_FALSE;
    };
    let ok = ctx
        .cmd_tx
        .send(SessionCommand::CreateChannel {
            name: jstr(&mut env, &name),
            parent_id: parent_id.max(0) as u64,
            password: jstr(&mut env, &password),
            topic: jstr(&mut env, &topic),
            description: jstr(&mut env, &description),
            max_clients,
            channel_type,
            is_default: is_default == JNI_TRUE,
            opus_music: opus_music == JNI_TRUE,
            quality: (quality.clamp(1, 10)) as u8,
            needed_talk_power: needed_talk_power.max(0),
            auto_join: auto_join == JNI_TRUE,
        })
        .is_ok();
    if ok { JNI_TRUE } else { JNI_FALSE }
}

#[no_mangle]
pub extern "system" fn Java_com_cloudwhile_tscc_data_NativeBridge_nativeEditChannel(
    mut env: JNIEnv,
    _class: JClass,
    channel_id: jlong,
    parent_id: jlong,
    name: JString,
    password_mode: jint,
    password: JString,
    topic: JString,
    description: JString,
    max_clients: jint,
    channel_type: jint,
    is_default: jboolean,
    opus_music: jboolean,
    quality: jint,
    needed_talk_power: jint,
) -> jboolean {
    let Some(ctx) = ENGINE.get() else {
        return JNI_FALSE;
    };
    let ok = ctx
        .cmd_tx
        .send(SessionCommand::EditChannel {
            channel_id: channel_id.max(0) as u64,
            parent_id,
            name: jstr(&mut env, &name),
            password_mode,
            password: jstr(&mut env, &password),
            topic: jstr(&mut env, &topic),
            description: jstr(&mut env, &description),
            max_clients,
            channel_type,
            is_default: is_default == JNI_TRUE,
            opus_music: opus_music == JNI_TRUE,
            quality: (quality.clamp(1, 10)) as u8,
            needed_talk_power: needed_talk_power.max(0),
        })
        .is_ok();
    if ok { JNI_TRUE } else { JNI_FALSE }
}

#[no_mangle]
pub extern "system" fn Java_com_cloudwhile_tscc_data_NativeBridge_nativeDeleteChannel(
    _env: JNIEnv,
    _class: JClass,
    channel_id: jlong,
    force: jboolean,
) -> jboolean {
    let Some(ctx) = ENGINE.get() else {
        return JNI_FALSE;
    };
    let ok = ctx
        .cmd_tx
        .send(SessionCommand::DeleteChannel {
            channel_id: channel_id.max(0) as u64,
            force: force == JNI_TRUE,
        })
        .is_ok();
    if ok { JNI_TRUE } else { JNI_FALSE }
}

#[no_mangle]
pub extern "system" fn Java_com_cloudwhile_tscc_data_NativeBridge_nativeRequestChannelDescription(
    _env: JNIEnv,
    _class: JClass,
    channel_id: jlong,
) -> jboolean {
    let Some(ctx) = ENGINE.get() else {
        return JNI_FALSE;
    };
    let ok = ctx
        .cmd_tx
        .send(SessionCommand::RequestChannelDescription {
            channel_id: channel_id.max(0) as u64,
        })
        .is_ok();
    if ok { JNI_TRUE } else { JNI_FALSE }
}

#[no_mangle]
pub extern "system" fn Java_com_cloudwhile_tscc_data_NativeBridge_nativeMoveClient(
    mut env: JNIEnv,
    _class: JClass,
    client_id: jint,
    target_channel_id: jlong,
    password: JString,
) -> jboolean {
    let Some(ctx) = ENGINE.get() else {
        return JNI_FALSE;
    };
    let ok = ctx
        .cmd_tx
        .send(SessionCommand::MoveClient {
            client_id: client_id as u16,
            target_channel_id: target_channel_id as u64,
            password: jstr(&mut env, &password),
        })
        .is_ok();
    if ok { JNI_TRUE } else { JNI_FALSE }
}

#[no_mangle]
pub extern "system" fn Java_com_cloudwhile_tscc_data_NativeBridge_nativeKickClient(
    mut env: JNIEnv,
    _class: JClass,
    client_id: jint,
    from_server: jboolean,
    reason: JString,
) -> jboolean {
    let Some(ctx) = ENGINE.get() else {
        return JNI_FALSE;
    };
    let ok = ctx
        .cmd_tx
        .send(SessionCommand::KickClient {
            client_id: client_id as u16,
            from_server: from_server == JNI_TRUE,
            reason: jstr(&mut env, &reason),
        })
        .is_ok();
    if ok { JNI_TRUE } else { JNI_FALSE }
}

#[no_mangle]
pub extern "system" fn Java_com_cloudwhile_tscc_data_NativeBridge_nativeBanClient(
    mut env: JNIEnv,
    _class: JClass,
    client_id: jint,
    duration_seconds: jlong,
    reason: JString,
) -> jboolean {
    let Some(ctx) = ENGINE.get() else {
        return JNI_FALSE;
    };
    let ok = ctx
        .cmd_tx
        .send(SessionCommand::BanClient {
            client_id: client_id as u16,
            duration_secs: duration_seconds.max(0) as u64,
            reason: jstr(&mut env, &reason),
        })
        .is_ok();
    if ok { JNI_TRUE } else { JNI_FALSE }
}

#[no_mangle]
pub extern "system" fn Java_com_cloudwhile_tscc_data_NativeBridge_nativeRequestIconOrAvatar(
    mut env: JNIEnv,
    _class: JClass,
    channel_id: jlong,
    remote_path: JString,
    file_key: JString,
    dest_file_path: JString,
) -> jboolean {
    let Some(ctx) = ENGINE.get() else {
        return JNI_FALSE;
    };
    let ok = ctx
        .cmd_tx
        .send(SessionCommand::DownloadFile {
            channel_id: channel_id.max(0) as u64,
            remote_path: jstr(&mut env, &remote_path),
            file_key: jstr(&mut env, &file_key),
            dest_path: jstr(&mut env, &dest_file_path),
        })
        .is_ok();
    if ok { JNI_TRUE } else { JNI_FALSE }
}

#[no_mangle]
pub extern "system" fn Java_com_cloudwhile_tscc_data_NativeBridge_nativeSetupStream(
    mut env: JNIEnv,
    _class: JClass,
    name: JString,
    stream_type: jint,
    bitrate_kbps: jint,
    accessibility: jint,
    mode: jint,
    viewer_limit: jint,
    audio: jboolean,
) -> jboolean {
    let Some(ctx) = ENGINE.get() else {
        return JNI_FALSE;
    };
    let ok = ctx
        .cmd_tx
        .send(SessionCommand::SetupStream {
            name: jstr(&mut env, &name),
            stream_type,
            bitrate_kbps: bitrate_kbps.max(300) as u32,
            accessibility,
            mode,
            viewer_limit: viewer_limit.max(1) as u32,
            audio: audio == JNI_TRUE,
        })
        .is_ok();
    if ok { JNI_TRUE } else { JNI_FALSE }
}

#[no_mangle]
pub extern "system" fn Java_com_cloudwhile_tscc_data_NativeBridge_nativeStopStream(
    mut env: JNIEnv,
    _class: JClass,
    stream_id: JString,
) -> jboolean {
    let Some(ctx) = ENGINE.get() else {
        return JNI_FALSE;
    };
    let ok = ctx
        .cmd_tx
        .send(SessionCommand::StopStream {
            stream_id: jstr(&mut env, &stream_id),
        })
        .is_ok();
    if ok { JNI_TRUE } else { JNI_FALSE }
}

#[no_mangle]
pub extern "system" fn Java_com_cloudwhile_tscc_data_NativeBridge_nativeRequestStreamInfo(
    _env: JNIEnv,
    _class: JClass,
    client_id: jint,
) -> jboolean {
    let Some(ctx) = ENGINE.get() else {
        return JNI_FALSE;
    };
    let ok = ctx
        .cmd_tx
        .send(SessionCommand::RequestStreamInfo {
            client_id: client_id.max(0) as u16,
        })
        .is_ok();
    if ok { JNI_TRUE } else { JNI_FALSE }
}

#[no_mangle]
pub extern "system" fn Java_com_cloudwhile_tscc_data_NativeBridge_nativeJoinStreamRequest(
    mut env: JNIEnv,
    _class: JClass,
    stream_id: JString,
    streamer_client_id: jint,
    message: JString,
    is_remove: jboolean,
) -> jboolean {
    let Some(ctx) = ENGINE.get() else {
        return JNI_FALSE;
    };
    let ok = ctx
        .cmd_tx
        .send(SessionCommand::JoinStreamRequest {
            stream_id: jstr(&mut env, &stream_id),
            streamer_client_id: streamer_client_id.max(0) as u16,
            message: jstr(&mut env, &message),
            is_remove: is_remove == JNI_TRUE,
        })
        .is_ok();
    if ok { JNI_TRUE } else { JNI_FALSE }
}

#[no_mangle]
pub extern "system" fn Java_com_cloudwhile_tscc_data_NativeBridge_nativeRespondJoinStreamRequest(
    mut env: JNIEnv,
    _class: JClass,
    stream_id: JString,
    viewer_client_id: jint,
    decision: jboolean,
    offer: JString,
    message: JString,
) -> jboolean {
    let Some(ctx) = ENGINE.get() else {
        return JNI_FALSE;
    };
    let ok = ctx
        .cmd_tx
        .send(SessionCommand::RespondJoinStreamRequest {
            stream_id: jstr(&mut env, &stream_id),
            viewer_client_id: viewer_client_id.max(0) as u16,
            decision: decision == JNI_TRUE,
            offer: jstr(&mut env, &offer),
            message: jstr(&mut env, &message),
        })
        .is_ok();
    if ok { JNI_TRUE } else { JNI_FALSE }
}

#[no_mangle]
pub extern "system" fn Java_com_cloudwhile_tscc_data_NativeBridge_nativeSendStreamSignaling(
    mut env: JNIEnv,
    _class: JClass,
    stream_id: JString,
    target_client_id: jint,
    json: JString,
) -> jboolean {
    let Some(ctx) = ENGINE.get() else {
        return JNI_FALSE;
    };
    let ok = ctx
        .cmd_tx
        .send(SessionCommand::SendStreamSignaling {
            stream_id: jstr(&mut env, &stream_id),
            target_client_id: target_client_id.max(0) as u16,
            json: jstr(&mut env, &json),
        })
        .is_ok();
    if ok { JNI_TRUE } else { JNI_FALSE }
}

#[no_mangle]
pub extern "system" fn Java_com_cloudwhile_tscc_data_NativeBridge_nativeRemoveClientFromStream(
    mut env: JNIEnv,
    _class: JClass,
    stream_id: JString,
    viewer_client_id: jint,
    reason: jint,
) -> jboolean {
    let Some(ctx) = ENGINE.get() else {
        return JNI_FALSE;
    };
    let ok = ctx
        .cmd_tx
        .send(SessionCommand::RemoveClientFromStream {
            stream_id: jstr(&mut env, &stream_id),
            viewer_client_id: viewer_client_id.max(0) as u16,
            reason,
        })
        .is_ok();
    if ok { JNI_TRUE } else { JNI_FALSE }
}

#[no_mangle]
pub extern "system" fn Java_com_cloudwhile_tscc_data_NativeBridge_nativeRequestServerVariables(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    let Some(ctx) = ENGINE.get() else {
        return JNI_FALSE;
    };
    if ctx.cmd_tx.send(SessionCommand::RequestServerVariables).is_ok() {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

#[no_mangle]
pub extern "system" fn Java_com_cloudwhile_tscc_data_NativeBridge_nativeRequestClientDetails(
    _env: JNIEnv,
    _class: JClass,
    client_id: jint,
) -> jboolean {
    let Some(ctx) = ENGINE.get() else {
        return JNI_FALSE;
    };
    if ctx
        .cmd_tx
        .send(SessionCommand::RequestClientDetails {
            client_id: client_id.max(0) as u16,
        })
        .is_ok()
    {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

#[no_mangle]
pub extern "system" fn Java_com_cloudwhile_tscc_data_NativeBridge_nativeUpdateSelfProfile(
    mut env: JNIEnv,
    _class: JClass,
    update_nickname: jboolean,
    nickname: JString,
    update_commander: jboolean,
    is_channel_commander: jboolean,
) -> jboolean {
    let Some(ctx) = ENGINE.get() else {
        return JNI_FALSE;
    };
    let nick_opt = if update_nickname == JNI_TRUE {
        Some(jstr(&mut env, &nickname))
    } else {
        None
    };
    let cmd_opt = if update_commander == JNI_TRUE {
        Some(is_channel_commander == JNI_TRUE)
    } else {
        None
    };
    if ctx
        .cmd_tx
        .send(SessionCommand::UpdateSelfProfile {
            nickname: nick_opt,
            is_channel_commander: cmd_opt,
        })
        .is_ok()
    {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

#[no_mangle]
pub extern "system" fn Java_com_cloudwhile_tscc_data_NativeBridge_nativeEditClientProperties(
    mut env: JNIEnv,
    _class: JClass,
    client_id: jint,
    update_description: jboolean,
    description: JString,
    update_talk_power_granted: jboolean,
    talk_power_granted: jboolean,
) -> jboolean {
    let Some(ctx) = ENGINE.get() else {
        return JNI_FALSE;
    };
    let desc_opt = if update_description == JNI_TRUE {
        Some(jstr(&mut env, &description))
    } else {
        None
    };
    let tp_opt = if update_talk_power_granted == JNI_TRUE {
        Some(talk_power_granted == JNI_TRUE)
    } else {
        None
    };
    if ctx
        .cmd_tx
        .send(SessionCommand::EditClientProperties {
            client_id: client_id.max(0) as u16,
            description: desc_opt,
            talk_power_granted: tp_opt,
        })
        .is_ok()
    {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

#[no_mangle]
pub extern "system" fn Java_com_cloudwhile_tscc_data_NativeBridge_nativeSetClientServerGroup(
    _env: JNIEnv,
    _class: JClass,
    client_id: jint,
    server_group_id: jlong,
    add: jboolean,
) -> jboolean {
    let Some(ctx) = ENGINE.get() else {
        return JNI_FALSE;
    };
    if ctx
        .cmd_tx
        .send(SessionCommand::SetClientServerGroup {
            client_id: client_id.max(0) as u16,
            server_group_id: server_group_id.max(0) as u64,
            add: add == JNI_TRUE,
        })
        .is_ok()
    {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

#[no_mangle]
pub extern "system" fn Java_com_cloudwhile_tscc_data_NativeBridge_nativeSetClientChannelGroup(
    _env: JNIEnv,
    _class: JClass,
    client_id: jint,
    channel_id: jlong,
    channel_group_id: jlong,
) -> jboolean {
    let Some(ctx) = ENGINE.get() else {
        return JNI_FALSE;
    };
    if ctx
        .cmd_tx
        .send(SessionCommand::SetClientChannelGroup {
            client_id: client_id.max(0) as u16,
            channel_id: channel_id.max(0) as u64,
            channel_group_id: channel_group_id.max(0) as u64,
        })
        .is_ok()
    {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}
