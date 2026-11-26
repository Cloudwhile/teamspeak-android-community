use std::sync::atomic::{AtomicBool, Ordering};
use serde::{Deserialize, Serialize};

pub static IS_ENGLISH: AtomicBool = AtomicBool::new(false);

#[inline]
pub fn is_en() -> bool {
    IS_ENGLISH.load(Ordering::Relaxed)
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ChannelNodeDto {
    pub id: i64,
    pub parent_id: i64,
    pub order: i64,
    pub name: String,
    pub topic: String,
    pub description: String,
    pub has_password: bool,
    pub is_permanent: bool,
    pub is_semi_permanent: bool,
    pub is_default: bool,
    pub max_clients: i32,
    pub needed_talk_power: i32,
    pub codec: i32,
    pub codec_quality: i32,
    pub icon_id: i64,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct OnlineClientDto {
    pub id: i32,
    pub channel_id: i64,
    pub uid: String,
    pub nickname: String,
    pub is_talking: bool,
    pub input_muted: bool,
    pub output_muted: bool,
    pub input_hardware: bool,
    pub output_hardware: bool,
    pub is_away: bool,
    pub away_message: String,
    pub is_channel_commander: bool,
    pub talk_power: i32,
    pub talk_power_granted: bool,
    pub server_groups: Vec<i64>,
    pub channel_group: i64,
    pub icon_id: i64,
    pub avatar_flag: String,
    pub client_type: i32,
    pub is_streaming: bool,
    pub stream_id: String,
    pub stream_name: String,
    pub stream_type: i32,
    pub stream_audio: bool,
    pub stream_bitrate: i32,
    pub stream_viewers: i32,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ChatMessageDto {
    pub id: String,
    pub target: String, // "SERVER" | "CHANNEL" | "CLIENT"
    pub peer_client_id: Option<i32>,
    pub peer_uid: Option<String>,
    pub peer_name: String,
    pub sender_id: i32,
    pub sender_name: String,
    pub sender_uid: String,
    pub content: String,
    pub timestamp_ms: i64,
    pub is_system: bool,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PokeAlertDto {
    pub id: String,
    pub from_client_id: i32,
    pub from_name: String,
    pub from_uid: String,
    pub message: String,
    pub timestamp_ms: i64,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ServerSnapshotDto {
    pub session_id: i64,
    pub server_name: String,
    pub server_address: String,
    pub welcome_message: String,
    pub host_message: String,
    pub platform: String,
    pub version: String,
    pub max_clients: i32,
    pub my_client_id: i32,
    pub my_channel_id: i64,
    pub server_icon_id: i64,
    pub ping_ms: i32,
    pub packet_loss_pct: f32,
    pub channels: Vec<ChannelNodeDto>,
    pub clients: Vec<OnlineClientDto>,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct IdentityInfoDto {
    pub ini_content: String,
    pub public_key_base64: String,
    pub unique_id: String,
    pub security_level: i32,
    pub key_offset: i64,
    #[serde(default)]
    pub cancelled: bool,
}

#[derive(Clone, Debug, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Ts6StreamSignalDto {
    pub event_type: String,
    pub client_id: i32,
    pub stream_id: String,
    pub name: String,
    pub stream_type: i32,
    pub accessibility: i32,
    pub mode: i32,
    pub bitrate: u32,
    pub viewer_limit: u32,
    pub audio: bool,
    pub viewer_count: u32,
    pub reason: i32,
    pub is_remove: bool,
    pub decision: bool,
    pub message: String,
    pub offer: String,
    pub json: String,
}

#[derive(Clone, Debug, Default, Serialize, Deserialize)]
pub struct CoreEventEnvelope {
    pub kind: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub status: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub reason: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub snapshot: Option<ServerSnapshotDto>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub chat: Option<ChatMessageDto>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub poke: Option<PokeAlertDto>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub speaking_ids: Option<Vec<i32>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub input_db: Option<f32>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub voice_transmitting: Option<bool>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub security_level: Option<i32>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub key_offset: Option<i64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub updated_ini: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub unique_id: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub file_key: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub local_path: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub action: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub command_ok: Option<bool>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub error_code: Option<u16>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub missing_perm: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub is_permission_error: Option<bool>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub target_channel_id: Option<i64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub cue: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub stream_signal: Option<Ts6StreamSignalDto>,
}
