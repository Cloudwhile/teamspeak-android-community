use std::collections::{HashMap, HashSet};

use tsproto_packets::packets::{Direction, Flags, OutCommand, PacketType};

use crate::events::Ts6StreamSignalDto;

// TS6 wire protocol enum values (protobuf enum value - 1, verified against tsserver 6.0.0-beta14):
// StreamType: NONE=0, CAMERA=1, SCREEN=2, WINDOW=3, EXISTING_SESSION=4, VOICE=5
pub const STREAM_TYPE_CAMERA: i32 = 1;
pub const STREAM_TYPE_SCREEN: i32 = 2;
pub const STREAM_TYPE_WINDOW: i32 = 3;

// StreamAccessibility: NONE=0, PUBLIC=1, CONTACTS_ONLY=2, PRIVATE=3
pub const STREAM_ACCESS_NONE: i32 = 0;
pub const STREAM_ACCESS_PUBLIC: i32 = 1;

// StreamMode: NONE=0, P2P=1, SFU=2
pub const STREAM_MODE_P2P: i32 = 1;

#[derive(Clone, Debug, Default)]
pub struct ActiveStreamState {
    pub stream_id: String,
    pub streamer_clid: u16,
    pub name: String,
    pub stream_type: i32,
    pub accessibility: i32,
    pub mode: i32,
    pub bitrate: u32,
    pub viewer_limit: u32,
    pub audio: bool,
    pub viewer_count: u32,
}

#[derive(Default)]
pub struct Ts6StreamTracker {
    pub streams_by_clid: HashMap<u16, ActiveStreamState>,
    pub streaming_clids: HashSet<u16>,
}

pub struct RawStreamParseOutcome {
    pub snapshot_dirty: bool,
    pub request_info_clids: Vec<u16>,
    pub signals: Vec<Ts6StreamSignalDto>,
}

impl Ts6StreamTracker {
    pub fn on_client_removed(&mut self, clid: u16) -> Option<Ts6StreamSignalDto> {
        self.streaming_clids.remove(&clid);
        self.streams_by_clid.remove(&clid).map(|st| Ts6StreamSignalDto {
            event_type: "stopped".to_string(),
            client_id: i32::from(clid),
            stream_id: st.stream_id,
            name: st.name,
            stream_type: st.stream_type,
            reason: 2,
            ..Default::default()
        })
    }

    pub fn handle_raw_command(&mut self, raw: &str) -> RawStreamParseOutcome {
        let mut out = RawStreamParseOutcome {
            snapshot_dirty: false,
            request_info_clids: Vec::new(),
            signals: Vec::new(),
        };

        let (cmd_name, records) = parse_ts_command_records(raw);
        if cmd_name.is_empty() || records.is_empty() {
            return out;
        }

        match cmd_name.as_str() {
            "notifycliententerview" | "notifyclientupdated" | "clientlist" => {
                for rec in &records {
                    let Some(clid) = rec.get("clid").and_then(|v| v.parse::<u16>().ok()) else {
                        continue;
                    };
                    let Some(flag) = rec.get("client_is_streaming") else {
                        continue;
                    };
                    if flag == "1" {
                        if self.streaming_clids.insert(clid) {
                            out.snapshot_dirty = true;
                        }
                        if !self.streams_by_clid.contains_key(&clid) {
                            out.request_info_clids.push(clid);
                        }
                    } else if flag == "0" {
                        let removed_flag = self.streaming_clids.remove(&clid);
                        let removed_st = self.streams_by_clid.remove(&clid);
                        if removed_flag || removed_st.is_some() {
                            out.snapshot_dirty = true;
                        }
                        if let Some(st) = removed_st {
                            out.signals.push(Ts6StreamSignalDto {
                                event_type: "stopped".to_string(),
                                client_id: i32::from(clid),
                                stream_id: st.stream_id,
                                name: st.name,
                                stream_type: st.stream_type,
                                ..Default::default()
                            });
                        }
                    }
                }
            }

            "notifystreamstarted" | "notifystreamupdated" | "notifystreaminfo" => {
                let ev_kind = match cmd_name.as_str() {
                    "notifystreamstarted" => "started",
                    "notifystreamupdated" => "updated",
                    _ => "info",
                };
                for rec in &records {
                    let Some(clid) = rec.get("clid").and_then(|v| v.parse::<u16>().ok()) else {
                        continue;
                    };
                    let prev = self.streams_by_clid.get(&clid).cloned().unwrap_or_default();
                    let stream_id = rec
                        .get("id")
                        .cloned()
                        .filter(|s| !s.is_empty())
                        .unwrap_or(prev.stream_id);
                    let name = rec.get("name").cloned().unwrap_or(prev.name);
                    let stream_type = rec
                        .get("type")
                        .and_then(|v| v.parse::<i32>().ok())
                        .unwrap_or_else(|| {
                            if prev.stream_type != 0 {
                                prev.stream_type
                            } else {
                                STREAM_TYPE_SCREEN
                            }
                        });
                    let accessibility = rec
                        .get("access")
                        .or_else(|| rec.get("accessibility"))
                        .and_then(|v| v.parse::<i32>().ok())
                        .unwrap_or_else(|| {
                            if prev.accessibility != 0 {
                                prev.accessibility
                            } else {
                                STREAM_ACCESS_PUBLIC
                            }
                        });
                    let mode = rec
                        .get("mode")
                        .and_then(|v| v.parse::<i32>().ok())
                        .unwrap_or_else(|| {
                            if prev.mode != 0 {
                                prev.mode
                            } else {
                                STREAM_MODE_P2P
                            }
                        });
                    let bitrate = rec
                        .get("bitrate")
                        .and_then(|v| v.parse::<u32>().ok())
                        .unwrap_or(prev.bitrate);
                    let viewer_limit = rec
                        .get("viewer_limit")
                        .and_then(|v| v.parse::<u32>().ok())
                        .unwrap_or(prev.viewer_limit);
                    let audio = rec
                        .get("audio")
                        .map(|v| v == "1" || v.eq_ignore_ascii_case("true"))
                        .unwrap_or(prev.audio);
                    let viewer_count = rec
                        .get("viewer")
                        .and_then(|v| v.parse::<u32>().ok())
                        .unwrap_or(prev.viewer_count);

                    let state = ActiveStreamState {
                        stream_id: stream_id.clone(),
                        streamer_clid: clid,
                        name: name.clone(),
                        stream_type,
                        accessibility,
                        mode,
                        bitrate,
                        viewer_limit,
                        audio,
                        viewer_count,
                    };
                    self.streaming_clids.insert(clid);
                    self.streams_by_clid.insert(clid, state);
                    out.snapshot_dirty = true;
                    out.signals.push(Ts6StreamSignalDto {
                        event_type: ev_kind.to_string(),
                        client_id: i32::from(clid),
                        stream_id,
                        name,
                        stream_type,
                        accessibility,
                        mode,
                        bitrate,
                        viewer_limit,
                        audio,
                        viewer_count,
                        ..Default::default()
                    });
                }
            }

            "notifystreamstopped" => {
                for rec in &records {
                    let clid_opt = rec.get("clid").and_then(|v| v.parse::<u16>().ok());
                    let stream_id = rec.get("id").cloned().unwrap_or_default();
                    let reason = rec
                        .get("reason")
                        .and_then(|v| v.parse::<i32>().ok())
                        .unwrap_or(0);

                    let resolved_clid = clid_opt.or_else(|| {
                        self.streams_by_clid
                            .iter()
                            .find(|(_, st)| !stream_id.is_empty() && st.stream_id == stream_id)
                            .map(|(&k, _)| k)
                    });

                    if let Some(clid) = resolved_clid {
                        self.streaming_clids.remove(&clid);
                        let prev = self.streams_by_clid.remove(&clid);
                        out.snapshot_dirty = true;
                        out.signals.push(Ts6StreamSignalDto {
                            event_type: "stopped".to_string(),
                            client_id: i32::from(clid),
                            stream_id: if stream_id.is_empty() {
                                prev.map(|p| p.stream_id).unwrap_or_default()
                            } else {
                                stream_id
                            },
                            reason,
                            ..Default::default()
                        });
                    }
                }
            }

            "notifystreamclientjoined" | "notifystreamclientleft" => {
                let joined = cmd_name == "notifystreamclientjoined";
                for rec in &records {
                    let viewer_clid = rec
                        .get("clid")
                        .and_then(|v| v.parse::<u16>().ok())
                        .unwrap_or(0);
                    let stream_id = rec.get("id").cloned().unwrap_or_default();
                    let reason = rec
                        .get("reason")
                        .and_then(|v| v.parse::<i32>().ok())
                        .unwrap_or(0);

                    let mut updated_viewers = 0u32;
                    for st in self.streams_by_clid.values_mut() {
                        if !stream_id.is_empty() && st.stream_id == stream_id {
                            if joined {
                                st.viewer_count = st.viewer_count.saturating_add(1);
                            } else {
                                st.viewer_count = st.viewer_count.saturating_sub(1);
                            }
                            updated_viewers = st.viewer_count;
                            out.snapshot_dirty = true;
                        }
                    }

                    out.signals.push(Ts6StreamSignalDto {
                        event_type: if joined {
                            "viewer_joined".to_string()
                        } else {
                            "viewer_left".to_string()
                        },
                        client_id: i32::from(viewer_clid),
                        stream_id,
                        viewer_count: updated_viewers,
                        reason,
                        ..Default::default()
                    });
                }
            }

            "notifyjoinstreamrequest" => {
                for rec in &records {
                    let viewer_clid = rec
                        .get("clid")
                        .and_then(|v| v.parse::<u16>().ok())
                        .unwrap_or(0);
                    let stream_id = rec.get("id").cloned().unwrap_or_default();
                    let is_remove = rec
                        .get("is_remove")
                        .map(|v| v == "1" || v.eq_ignore_ascii_case("true"))
                        .unwrap_or(false);
                    let message = rec
                        .get("msg")
                        .or_else(|| rec.get("message"))
                        .cloned()
                        .unwrap_or_default();

                    out.signals.push(Ts6StreamSignalDto {
                        event_type: "join_request".to_string(),
                        client_id: i32::from(viewer_clid),
                        stream_id,
                        is_remove,
                        message,
                        ..Default::default()
                    });
                }
            }

            "notifyrespondjoinstreamrequest" => {
                for rec in &records {
                    let streamer_clid = rec
                        .get("clid")
                        .and_then(|v| v.parse::<u16>().ok())
                        .unwrap_or(0);
                    let stream_id = rec.get("id").cloned().unwrap_or_default();
                    let decision = rec
                        .get("decision")
                        .map(|v| v == "1" || v.eq_ignore_ascii_case("true"))
                        .unwrap_or(false);
                    let offer = rec.get("offer").cloned().unwrap_or_default();
                    let message = rec
                        .get("msg")
                        .or_else(|| rec.get("message"))
                        .cloned()
                        .unwrap_or_default();

                    out.signals.push(Ts6StreamSignalDto {
                        event_type: "join_response".to_string(),
                        client_id: i32::from(streamer_clid),
                        stream_id,
                        decision,
                        offer,
                        message,
                        ..Default::default()
                    });
                }
            }

            "notifystreamsignaling" => {
                for rec in &records {
                    let peer_clid = rec
                        .get("clid")
                        .and_then(|v| v.parse::<u16>().ok())
                        .unwrap_or(0);
                    let stream_id = rec.get("id").cloned().unwrap_or_default();
                    let json = rec.get("json").cloned().unwrap_or_default();

                    out.signals.push(Ts6StreamSignalDto {
                        event_type: "signaling".to_string(),
                        client_id: i32::from(peer_clid),
                        stream_id,
                        json,
                        ..Default::default()
                    });
                }
            }

            _ => {}
        }

        out
    }
}

pub fn build_setup_stream_cmd(
    name: &str,
    stream_type: i32,
    bitrate_kbps: u32,
    accessibility: i32,
    mode: i32,
    viewer_limit: u32,
    audio: bool,
) -> OutCommand {
    let mut pkt = OutCommand::new(
        Direction::C2S,
        Flags::empty(),
        PacketType::Command,
        "setupstream",
    );
    pkt.write_arg("name", &name);
    pkt.write_arg("type", &stream_type);
    pkt.write_arg("bitrate", &bitrate_kbps);
    pkt.write_arg("accessibility", &accessibility);
    pkt.write_arg("mode", &mode);
    pkt.write_arg("viewer_limit", &viewer_limit);
    pkt.write_arg("audio", &(if audio { 1u8 } else { 0u8 }));
    pkt
}

pub fn build_stop_stream_cmd(stream_id: &str, reason: i32) -> OutCommand {
    let mut pkt = OutCommand::new(
        Direction::C2S,
        Flags::empty(),
        PacketType::Command,
        "stopstream",
    );
    pkt.write_arg("id", &stream_id);
    pkt.write_arg("reason", &reason.max(0));
    pkt
}

pub fn build_request_stream_info_cmd(clid: u16) -> OutCommand {
    let mut pkt = OutCommand::new(
        Direction::C2S,
        Flags::empty(),
        PacketType::Command,
        "requeststreaminfo",
    );
    pkt.write_arg("clid", &clid);
    pkt
}

pub fn build_join_stream_request_cmd(
    stream_id: &str,
    streamer_clid: u16,
    message: &str,
    is_remove: bool,
) -> OutCommand {
    let mut pkt = OutCommand::new(
        Direction::C2S,
        Flags::empty(),
        PacketType::Command,
        "joinstreamrequest",
    );
    pkt.write_arg("id", &stream_id);
    pkt.write_arg("clid", &streamer_clid);
    pkt.write_arg("msg", &message);
    pkt.write_arg("is_remove", &(if is_remove { 1u8 } else { 0u8 }));
    pkt
}

pub fn build_respond_join_stream_cmd(
    stream_id: &str,
    viewer_clid: u16,
    decision: bool,
    offer: &str,
    message: &str,
) -> OutCommand {
    let mut pkt = OutCommand::new(
        Direction::C2S,
        Flags::empty(),
        PacketType::Command,
        "respondjoinstreamrequest",
    );
    pkt.write_arg("id", &stream_id);
    pkt.write_arg("clid", &viewer_clid);
    pkt.write_arg("decision", &(if decision { 1u8 } else { 0u8 }));
    pkt.write_arg("offer", &offer);
    pkt.write_arg("msg", &message);
    pkt
}

pub fn build_stream_signaling_cmd(stream_id: &str, target_clid: u16, json: &str) -> OutCommand {
    let mut pkt = OutCommand::new(
        Direction::C2S,
        Flags::empty(),
        PacketType::Command,
        "streamsignaling",
    );
    pkt.write_arg("id", &stream_id);
    pkt.write_arg("clid", &target_clid);
    pkt.write_arg("json", &json);
    pkt
}

pub fn build_remove_client_from_stream_cmd(
    stream_id: &str,
    viewer_clid: u16,
    reason: i32,
) -> OutCommand {
    let mut pkt = OutCommand::new(
        Direction::C2S,
        Flags::empty(),
        PacketType::Command,
        "removeclientfromstream",
    );
    pkt.write_arg("id", &stream_id);
    pkt.write_arg("clid", &viewer_clid);
    pkt.write_arg("reason", &reason.max(0));
    pkt
}

pub fn unescape_ts_value(raw: &str) -> String {
    let mut out = String::with_capacity(raw.len());
    let mut chars = raw.chars();
    while let Some(ch) = chars.next() {
        if ch == '\\' {
            match chars.next() {
                Some('\\') => out.push('\\'),
                Some('/') => out.push('/'),
                Some('s') => out.push(' '),
                Some('p') => out.push('|'),
                Some('a') => out.push('\x07'),
                Some('b') => out.push('\x08'),
                Some('f') => out.push('\x0c'),
                Some('n') => out.push('\n'),
                Some('r') => out.push('\r'),
                Some('t') => out.push('\t'),
                Some('v') => out.push('\x0b'),
                Some(other) => {
                    out.push('\\');
                    out.push(other);
                }
                None => out.push('\\'),
            }
        } else {
            out.push(ch);
        }
    }
    out
}

pub fn parse_ts_command_records(raw: &str) -> (String, Vec<HashMap<String, String>>) {
    let trimmed = raw.trim_matches(|c| c == '\0' || c == '\r' || c == '\n' || c == ' ');
    if trimmed.is_empty() {
        return (String::new(), Vec::new());
    }

    let mut cmd_name = String::new();
    let mut records = Vec::new();
    let mut sticky: HashMap<String, String> = HashMap::new();

    for (idx, part) in trimmed.split('|').enumerate() {
        let mut tokens = part.split_whitespace();
        if idx == 0 {
            if let Some(first) = tokens.next() {
                if first.contains('=') {
                    let mut map = sticky.clone();
                    insert_kv(&mut map, first);
                    for tok in tokens {
                        insert_kv(&mut map, tok);
                    }
                    sticky = map.clone();
                    records.push(map);
                    continue;
                } else {
                    cmd_name = first.to_string();
                }
            }
        }

        let mut map = sticky.clone();
        let mut any = false;
        for tok in tokens {
            insert_kv(&mut map, tok);
            any = true;
        }
        if any || idx == 0 {
            sticky = map.clone();
            records.push(map);
        }
    }

    (cmd_name, records)
}

fn insert_kv(map: &mut HashMap<String, String>, token: &str) {
    if let Some((k, v)) = token.split_once('=') {
        map.insert(k.to_string(), unescape_ts_value(v));
    } else if !token.is_empty() {
        map.insert(token.to_string(), String::new());
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_ts6_stream_lifecycle_and_signaling() {
        let mut tracker = Ts6StreamTracker::default();

        let enter = tracker.handle_raw_command(
            "notifycliententerview ctid=1 clid=7 client_nickname=Streamer client_is_streaming=1|clid=8 client_nickname=Listener client_is_streaming=0",
        );
        assert!(enter.snapshot_dirty);
        assert_eq!(enter.request_info_clids, vec![7]);
        assert!(tracker.streaming_clids.contains(&7));
        assert!(!tracker.streaming_clids.contains(&8));

        let started = tracker.handle_raw_command(
            "notifystreamstarted clid=7 id=abc-123 name=Desktop\\s1080p type=2 access=1 mode=1 bitrate=4500 viewer_limit=10 audio=1",
        );
        assert!(started.snapshot_dirty);
        assert_eq!(started.signals.len(), 1);
        assert_eq!(started.signals[0].event_type, "started");
        assert_eq!(started.signals[0].stream_id, "abc-123");
        assert_eq!(started.signals[0].name, "Desktop 1080p");
        assert_eq!(started.signals[0].stream_type, STREAM_TYPE_SCREEN);
        assert!(started.signals[0].audio);

        let joined = tracker.handle_raw_command("notifystreamclientjoined clid=8 id=abc-123");
        assert_eq!(joined.signals[0].viewer_count, 1);
        assert_eq!(tracker.streams_by_clid.get(&7).unwrap().viewer_count, 1);

        let sig = tracker.handle_raw_command(
            "notifystreamsignaling clid=7 id=abc-123 json={\"type\":\"answer\",\"sdp\":\"v=0\\r\\n\"}",
        );
        assert_eq!(sig.signals[0].event_type, "signaling");
        assert_eq!(
            sig.signals[0].json,
            "{\"type\":\"answer\",\"sdp\":\"v=0\r\n\"}"
        );

        let stopped = tracker.handle_raw_command("notifystreamstopped clid=7 id=abc-123 reason=2");
        assert!(stopped.snapshot_dirty);
        assert!(!tracker.streaming_clids.contains(&7));
        assert!(!tracker.streams_by_clid.contains_key(&7));
    }

    #[test]
    fn stream_commands_always_include_all_mandatory_ts6_parameters() {
        let join_pkt = build_join_stream_request_cmd("stream-uuid-1", 12, "", false).into_packet();
        let join_cmd = String::from_utf8_lossy(join_pkt.content()).into_owned();
        let (name, recs) = parse_ts_command_records(&join_cmd);
        assert_eq!(name, "joinstreamrequest");
        assert_eq!(recs[0].get("id").map(String::as_str), Some("stream-uuid-1"));
        assert_eq!(recs[0].get("clid").map(String::as_str), Some("12"));
        assert!(recs[0].contains_key("msg"), "msg must always be serialized");
        assert_eq!(recs[0].get("is_remove").map(String::as_str), Some("0"));

        let resp_pkt =
            build_respond_join_stream_cmd("stream-uuid-1", 15, true, "v=0\r\n", "").into_packet();
        let resp_cmd = String::from_utf8_lossy(resp_pkt.content()).into_owned();
        let (rname, rrecs) = parse_ts_command_records(&resp_cmd);
        assert_eq!(rname, "respondjoinstreamrequest");
        assert_eq!(rrecs[0].get("decision").map(String::as_str), Some("1"));
        assert!(rrecs[0].contains_key("offer"));
        assert!(rrecs[0].contains_key("msg"));

        let stop_pkt = build_stop_stream_cmd("stream-uuid-1", 0).into_packet();
        let stop_cmd = String::from_utf8_lossy(stop_pkt.content()).into_owned();
        let (sname, srecs) = parse_ts_command_records(&stop_cmd);
        assert_eq!(sname, "stopstream");
        assert_eq!(srecs[0].get("id").map(String::as_str), Some("stream-uuid-1"));
        assert_eq!(srecs[0].get("reason").map(String::as_str), Some("0"));

        let setup_pkt = build_setup_stream_cmd(
            "My Live",
            STREAM_TYPE_SCREEN,
            3500,
            STREAM_ACCESS_PUBLIC,
            STREAM_MODE_P2P,
            12,
            false,
        )
        .into_packet();
        let setup_cmd = String::from_utf8_lossy(setup_pkt.content()).into_owned();
        let (up_name, up_recs) = parse_ts_command_records(&setup_cmd);
        assert_eq!(up_name, "setupstream");
        assert_eq!(up_recs[0].get("type").map(String::as_str), Some("2"));
        assert_eq!(up_recs[0].get("mode").map(String::as_str), Some("1"));
        assert_eq!(up_recs[0].get("accessibility").map(String::as_str), Some("1"));
    }
}
