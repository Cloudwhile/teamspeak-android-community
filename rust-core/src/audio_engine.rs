use std::collections::HashMap;

use audiopus::coder::Encoder;
use audiopus::{Application, Bitrate, Channels, SampleRate};
use tsclientlib::audio::AudioHandler;
use tsclientlib::ClientId;
use tsproto_packets::packets::{AudioData, CodecType, Direction, InAudioBuf, OutAudio, OutPacket};

pub const SAMPLE_RATE_HZ: usize = 48_000;
pub const MONO_FRAME_SAMPLES: usize = 960; // 20 ms at 48 kHz
pub const STEREO_FRAME_SAMPLES: usize = MONO_FRAME_SAMPLES * 2;
const VAD_HANGOVER_FRAMES: u32 = 12; // 240 ms tail to avoid clipping word endings
const LOOPBACK_VIRTUAL_CLIENT: ClientId = ClientId(65_534);

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum CaptureVoiceMode {
    Vad = 0,
    Ptt = 1,
    Continuous = 2,
}

impl CaptureVoiceMode {
    pub fn from_code(code: i32) -> Self {
        match code {
            1 => Self::Ptt,
            2 => Self::Continuous,
            _ => Self::Vad,
        }
    }
}

#[derive(Clone, Copy, Debug)]
pub struct UserAudioGain {
    pub gain: f32, // 0.0 ..= 2.0
    pub muted: bool,
}

impl Default for UserAudioGain {
    fn default() -> Self {
        Self {
            gain: 1.0,
            muted: false,
        }
    }
}

pub struct CaptureFrameOutcome {
    pub input_db: f32,
    pub is_transmitting: bool,
    pub outgoing_packet: Option<OutPacket>,
    pub loopback_packet: Option<InAudioBuf>,
}

/// 2nd-order Butterworth High-Pass Filter (Direct Form II Transposed) at 110 Hz.
/// Strips sub-bass breath, wind blasts, and close-mic plosive thumps (20–100 Hz) before VAD & Opus.
struct BiquadHpf {
    b0: f32,
    b1: f32,
    b2: f32,
    a1: f32,
    a2: f32,
    z1: f32,
    z2: f32,
}

impl BiquadHpf {
    fn new_butterworth(cutoff_hz: f64, sample_rate_hz: f64) -> Self {
        let w0 = 2.0 * std::f64::consts::PI * cutoff_hz / sample_rate_hz;
        let cos_w0 = w0.cos();
        let sin_w0 = w0.sin();
        let q = std::f64::consts::FRAC_1_SQRT_2;
        let alpha = sin_w0 / (2.0 * q);
        let a0 = 1.0 + alpha;

        let b0 = ((1.0 + cos_w0) * 0.5) / a0;
        let b1 = -(1.0 + cos_w0) / a0;
        let b2 = b0;
        let a1 = (-2.0 * cos_w0) / a0;
        let a2 = (1.0 - alpha) / a0;

        Self {
            b0: b0 as f32,
            b1: b1 as f32,
            b2: b2 as f32,
            a1: a1 as f32,
            a2: a2 as f32,
            z1: 0.0,
            z2: 0.0,
        }
    }

    #[inline]
    fn process(&mut self, x: f32) -> f32 {
        let y = self.b0 * x + self.z1;
        self.z1 = self.b1 * x - self.a1 * y + self.z2;
        self.z2 = self.b2 * x - self.a2 * y;
        y
    }
}

/// C1-continuous soft-knee saturator: strictly linear (`y = x`) for `|x| <= knee`,
/// smoothly approaching `±ceiling` above `knee` with continuous value AND derivative (`f'(knee) = 1`).
#[inline]
pub fn smooth_soft_clip(x: f32, knee: f32, ceiling: f32) -> f32 {
    let ax = x.abs();
    if ax <= knee {
        x
    } else {
        let span = (ceiling - knee).max(1e-4);
        let compressed = knee + span * ((ax - knee) / span).tanh();
        compressed.copysign(x)
    }
}

/// Real-time 48kHz Opus Voice Engine handling anti-plosive HPF + peak limiting, capture VAD/PTT gating,
/// Opus encoding, adaptive jitter-buffered multi-client decoding, 0–200% per-user gain, and C1 soft-clipping mixdown.
pub struct RealtimeAudioEngine {
    encoder: Encoder,
    opus_encode_buf: [u8; 1275],
    capture_float_buf: [f32; MONO_FRAME_SAMPLES],
    stereo_mix_buf: [f32; STEREO_FRAME_SAMPLES],

    pub mode: CaptureVoiceMode,
    pub vad_threshold_db: f32,
    pub ptt_active: bool,
    pub loopback_enabled: bool,
    pub anti_plosive_enabled: bool,
    pub local_capture_blocked: bool,
    pub server_can_send_audio: bool,

    capture_hpf: BiquadHpf,
    limiter_env: f32,
    plosive_duck_gain: f32,

    vad_hangover_remaining: u32,
    was_transmitting: bool,
    force_immediate_telemetry: bool,
    outgoing_seq_id: u16,
    capture_telemetry_decimator: u8,
    last_capture_silent_emitted: bool,
    last_emitted_speakers: Vec<i32>,
    target_codec: CodecType,
    target_quality: u8,

    audio_handler: AudioHandler<ClientId>,
    uid_by_client_id: HashMap<ClientId, String>,
    user_gains_by_uid: HashMap<String, UserAudioGain>,
}

impl RealtimeAudioEngine {
    pub fn new() -> Result<Self, String> {
        let mut encoder = Encoder::new(SampleRate::Hz48000, Channels::Mono, Application::Voip)
            .map_err(|e| format!("Failed to init Opus encoder: {e}"))?;
        let _ = encoder.set_bitrate(Bitrate::BitsPerSecond(38_400));
        let _ = encoder.set_inband_fec(true);

        Ok(Self {
            encoder,
            opus_encode_buf: [0u8; 1275],
            capture_float_buf: [0.0f32; MONO_FRAME_SAMPLES],
            stereo_mix_buf: [0.0f32; STEREO_FRAME_SAMPLES],
            mode: CaptureVoiceMode::Vad,
            vad_threshold_db: -42.0,
            ptt_active: false,
            loopback_enabled: false,
            anti_plosive_enabled: true,
            local_capture_blocked: false,
            server_can_send_audio: true,
            capture_hpf: BiquadHpf::new_butterworth(110.0, SAMPLE_RATE_HZ as f64),
            limiter_env: 0.0,
            plosive_duck_gain: 1.0,
            vad_hangover_remaining: 0,
            was_transmitting: false,
            force_immediate_telemetry: false,
            outgoing_seq_id: 0,
            capture_telemetry_decimator: 0,
            last_capture_silent_emitted: false,
            last_emitted_speakers: Vec::new(),
            target_codec: CodecType::OpusVoice,
            target_quality: 6,
            audio_handler: AudioHandler::new(),
            uid_by_client_id: HashMap::new(),
            user_gains_by_uid: HashMap::new(),
        })
    }

    pub fn reset_playback_queues(&mut self) {
        self.audio_handler.reset();
        self.uid_by_client_id.clear();
        // Force next pull_playback_pcm or disconnect handler to clear speakers
    }

    pub fn set_channel_audio_format(&mut self, codec_num: i32, quality: u8) {
        let want_codec = if codec_num == 5 {
            CodecType::OpusMusic
        } else {
            CodecType::OpusVoice
        };
        let want_quality = quality.clamp(1, 10);
        if want_codec == self.target_codec && want_quality == self.target_quality {
            return;
        }
        if want_codec != self.target_codec {
            let app = if want_codec == CodecType::OpusMusic {
                Application::Audio
            } else {
                Application::Voip
            };
            if let Ok(mut new_enc) = Encoder::new(SampleRate::Hz48000, Channels::Mono, app) {
                let _ = new_enc.set_inband_fec(true);
                self.encoder = new_enc;
            }
        }
        let bps = if want_codec == CodecType::OpusMusic {
            24_000 + (i32::from(want_quality) * 9_600)
        } else {
            12_000 + (i32::from(want_quality) * 4_400)
        };
        let _ = self.encoder.set_bitrate(Bitrate::BitsPerSecond(bps));
        self.target_codec = want_codec;
        self.target_quality = want_quality;
    }

    pub fn set_local_capture_blocked(&mut self, blocked: bool) {
        if self.local_capture_blocked != blocked {
            self.local_capture_blocked = blocked;
            if blocked && !self.loopback_enabled {
                self.vad_hangover_remaining = 0;
                self.force_immediate_telemetry = true;
            }
        }
    }

    pub fn set_server_can_send_audio(&mut self, can_send: bool) {
        if self.server_can_send_audio != can_send {
            self.server_can_send_audio = can_send;
            if !can_send && !self.loopback_enabled {
                self.vad_hangover_remaining = 0;
                self.force_immediate_telemetry = true;
            }
        }
    }

    pub fn update_client_uid_map(&mut self, map: HashMap<ClientId, String>) {
        self.uid_by_client_id = map;
        self.refresh_queue_volumes();
    }

    pub fn set_user_audio_pref(&mut self, uid: String, volume_percent: i32, muted: bool) {
        let gain = (volume_percent.clamp(0, 200) as f32) / 100.0;
        self.user_gains_by_uid.insert(uid, UserAudioGain { gain, muted });
        self.refresh_queue_volumes();
    }

    fn refresh_queue_volumes(&mut self) {
        for (cid, queue) in self.audio_handler.get_mut_queues().iter_mut() {
            if *cid == LOOPBACK_VIRTUAL_CLIENT {
                queue.volume = 1.0;
                continue;
            }
            if let Some(uid) = self.uid_by_client_id.get(cid) {
                if let Some(pref) = self.user_gains_by_uid.get(uid) {
                    queue.volume = if pref.muted { 0.0 } else { pref.gain };
                    continue;
                }
            }
            queue.volume = 1.0;
        }
    }

    /// Processes 960 16-bit mono PCM samples (20ms @ 48kHz).
    pub fn process_capture_pcm(&mut self, pcm_i16: &[i16]) -> CaptureFrameOutcome {
        debug_assert_eq!(pcm_i16.len(), MONO_FRAME_SAMPLES);

        // Envelope time constants at 48 kHz:
        // Peak limiter: 0.2ms fast attack (~0.10), 75ms smooth release (~0.00028)
        const LIM_ATTACK: f32 = 0.10;
        const LIM_RELEASE: f32 = 0.00028;
        const LIM_CEILING: f32 = 0.82; // ~ -1.7 dBFS clean ceiling before soft-clipper
        // Plosive transient ducking: 1.0ms attack (~0.02), 45ms release (~0.00046)
        const POP_ATTACK: f32 = 0.02;
        const POP_RELEASE: f32 = 0.00046;

        let anti_pop = self.anti_plosive_enabled;
        let mut sum_sq = 0.0f64;

        for (i, &sample) in pcm_i16.iter().enumerate() {
            let raw = (sample as f32) / 32768.0;
            let processed = if anti_pop {
                // 1. Strip sub-110Hz wind & breath pressure spike
                let filtered = self.capture_hpf.process(raw);
                let sub_bass_blast = (raw - filtered).abs();
                let voice_band = filtered.abs();

                // 2. Detect heavy close-mic plosive / breath blast & smoothly duck transient harmonics
                let target_duck = if sub_bass_blast > 0.16 && sub_bass_blast > voice_band * 1.45 {
                    0.38
                } else {
                    1.0
                };
                if target_duck < self.plosive_duck_gain {
                    self.plosive_duck_gain += POP_ATTACK * (target_duck - self.plosive_duck_gain);
                } else {
                    self.plosive_duck_gain += POP_RELEASE * (target_duck - self.plosive_duck_gain);
                }
                let de_popped = filtered * self.plosive_duck_gain;

                // 3. Fast-attack / musical-release envelope peak limiter + C1 soft-knee safety ceiling
                let abs_s = de_popped.abs();
                if abs_s > self.limiter_env {
                    self.limiter_env += LIM_ATTACK * (abs_s - self.limiter_env);
                } else {
                    self.limiter_env += LIM_RELEASE * (abs_s - self.limiter_env);
                }
                let lim_gain = if self.limiter_env > LIM_CEILING {
                    LIM_CEILING / self.limiter_env
                } else {
                    1.0
                };
                smooth_soft_clip(de_popped * lim_gain, 0.70, 0.89)
            } else {
                smooth_soft_clip(raw, 0.78, 0.96)
            };

            self.capture_float_buf[i] = processed;
            sum_sq += (processed as f64) * (processed as f64);
        }

        let rms = (sum_sq / (MONO_FRAME_SAMPLES as f64)).sqrt() as f32;
        let input_db = if rms > 1e-5 {
            (20.0 * rms.log10()).clamp(-90.0, 0.0)
        } else {
            -90.0
        };

        let transmission_blocked =
            (self.local_capture_blocked || !self.server_can_send_audio) && !self.loopback_enabled;

        let should_send = if transmission_blocked {
            self.vad_hangover_remaining = 0;
            false
        } else {
            match self.mode {
                CaptureVoiceMode::Continuous => true,
                CaptureVoiceMode::Ptt => self.ptt_active || self.loopback_enabled,
                CaptureVoiceMode::Vad => {
                    if input_db >= self.vad_threshold_db {
                        self.vad_hangover_remaining = VAD_HANGOVER_FRAMES;
                        true
                    } else if self.vad_hangover_remaining > 0 {
                        self.vad_hangover_remaining -= 1;
                        true
                    } else {
                        false
                    }
                }
            }
        };

        let mut outgoing_packet = None;
        let mut loopback_packet = None;

        if should_send {
            if let Ok(encoded_len) = self
                .encoder
                .encode_float(&self.capture_float_buf, &mut self.opus_encode_buf)
            {
                let seq = self.outgoing_seq_id;
                self.outgoing_seq_id = self.outgoing_seq_id.wrapping_add(1);
                let payload = &self.opus_encode_buf[..encoded_len];

                let active_codec = if self.loopback_enabled {
                    CodecType::OpusVoice
                } else {
                    self.target_codec
                };
                if self.loopback_enabled {
                    let s2c = OutAudio::new(&AudioData::S2C {
                        id: seq,
                        codec: active_codec,
                        from: LOOPBACK_VIRTUAL_CLIENT.0,
                        data: payload,
                    });
                    loopback_packet = InAudioBuf::try_new(Direction::S2C, s2c.into_vec()).ok();
                } else {
                    outgoing_packet = Some(OutAudio::new(&AudioData::C2S {
                        id: seq,
                        codec: active_codec,
                        data: payload,
                    }));
                }
            }
        } else if self.was_transmitting {
            // TeamSpeak protocol end-of-stream marker: empty payload clears peer speaking ring immediately
            let seq = self.outgoing_seq_id;
            self.outgoing_seq_id = self.outgoing_seq_id.wrapping_add(1);
            let active_codec = if self.loopback_enabled {
                CodecType::OpusVoice
            } else {
                self.target_codec
            };
            if self.loopback_enabled {
                let s2c = OutAudio::new(&AudioData::S2C {
                    id: seq,
                    codec: active_codec,
                    from: LOOPBACK_VIRTUAL_CLIENT.0,
                    data: &[],
                });
                loopback_packet = InAudioBuf::try_new(Direction::S2C, s2c.into_vec()).ok();
            } else {
                outgoing_packet = Some(OutAudio::new(&AudioData::C2S {
                    id: seq,
                    codec: active_codec,
                    data: &[],
                }));
            }
        }

        if self.was_transmitting != should_send {
            self.force_immediate_telemetry = true;
        }
        self.was_transmitting = should_send;

        if let Some(lp) = loopback_packet.take() {
            let _ = self.push_incoming_packet(LOOPBACK_VIRTUAL_CLIENT, lp);
        }

        CaptureFrameOutcome {
            input_db,
            is_transmitting: should_send,
            outgoing_packet,
            loopback_packet: None,
        }
    }

    /// Independent capture-only telemetry decimator (immune to playback thread lockstep parity)
    /// that also suppresses redundant `-90 dB` JSON wakeups during prolonged silence.
    pub fn should_emit_capture_telemetry(&mut self, input_db: f32) -> bool {
        self.capture_telemetry_decimator = self.capture_telemetry_decimator.wrapping_add(1);
        if self.force_immediate_telemetry {
            self.force_immediate_telemetry = false;
            self.last_capture_silent_emitted = input_db <= -89.5 && !self.was_transmitting;
            return true;
        }
        if self.capture_telemetry_decimator % 4 != 0 {
            return false;
        }
        if input_db <= -89.5 && !self.was_transmitting {
            if self.last_capture_silent_emitted {
                return false;
            }
            self.last_capture_silent_emitted = true;
            return true;
        }
        self.last_capture_silent_emitted = false;
        true
    }

    /// Returns `Some(sorted_speakers)` strictly when the set of active remote talkers changes,
    /// providing 0ms speaker ring updates and zero JNI polling overhead during steady states.
    pub fn take_changed_speakers(&mut self, current: &[i32]) -> Option<Vec<i32>> {
        let mut sorted = current.to_vec();
        sorted.sort_unstable();
        sorted.dedup();
        if sorted != self.last_emitted_speakers {
            self.last_emitted_speakers = sorted.clone();
            Some(sorted)
        } else {
            None
        }
    }

    pub fn push_incoming_packet(&mut self, from: ClientId, packet: InAudioBuf) -> bool {
        let added = self.audio_handler.handle_packet(from, packet).is_ok();
        if added {
            if let Some(queue) = self.audio_handler.get_mut_queues().get_mut(&from) {
                if from == LOOPBACK_VIRTUAL_CLIENT {
                    queue.volume = 1.0;
                } else if let Some(uid) = self.uid_by_client_id.get(&from) {
                    if let Some(pref) = self.user_gains_by_uid.get(uid) {
                        queue.volume = if pref.muted { 0.0 } else { pref.gain };
                    }
                }
            }
        }
        added
    }

    /// Mixes all active speakers into 960 mono 16-bit PCM samples using C1-continuous soft-knee saturation.
    pub fn pull_playback_pcm(&mut self, out_pcm_i16: &mut [i16]) -> (usize, Vec<i32>) {
        debug_assert_eq!(out_pcm_i16.len(), MONO_FRAME_SAMPLES);
        let active_total = self.audio_handler.get_queues().len();
        if active_total == 0 {
            out_pcm_i16.fill(0);
            return (0, Vec::new());
        }

        self.stereo_mix_buf.fill(0.0);
        self.audio_handler.fill_buffer(&mut self.stereo_mix_buf);

        for i in 0..MONO_FRAME_SAMPLES {
            let left = self.stereo_mix_buf[i * 2];
            let right = self.stereo_mix_buf[i * 2 + 1];
            let mono = (left + right) * 0.5;
            let soft_limited = smooth_soft_clip(mono, 0.72, 0.98);
            out_pcm_i16[i] = (soft_limited * 32767.0).clamp(-32768.0, 32767.0) as i16;
        }

        let real_speakers: Vec<i32> = self
            .audio_handler
            .get_queues()
            .keys()
            .filter(|&&cid| cid != LOOPBACK_VIRTUAL_CLIENT)
            .map(|cid| i32::from(cid.0))
            .collect();

        (active_total, real_speakers)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn opus_loopback_vad_and_soft_limiter_pipeline() {
        let mut engine = RealtimeAudioEngine::new().expect("init audio engine");
        engine.mode = CaptureVoiceMode::Vad;
        engine.vad_threshold_db = -45.0;
        engine.loopback_enabled = true;

        let silence = [0i16; MONO_FRAME_SAMPLES];
        let silent_res = engine.process_capture_pcm(&silence);
        assert!(!silent_res.is_transmitting);

        let mut tone = [0i16; MONO_FRAME_SAMPLES];
        for (n, sample) in tone.iter_mut().enumerate() {
            let phase = 2.0 * std::f32::consts::PI * 440.0 * (n as f32) / (SAMPLE_RATE_HZ as f32);
            *sample = (phase.sin() * 16000.0) as i16;
        }

        for _ in 0..4 {
            let res = engine.process_capture_pcm(&tone);
            assert!(res.is_transmitting);
            assert!(res.input_db > -20.0);
        }

        let mut out_pcm = [0i16; MONO_FRAME_SAMPLES];
        let mut any_non_zero = false;
        for _ in 0..4 {
            let (active, _) = engine.pull_playback_pcm(&mut out_pcm);
            if active > 0 && out_pcm.iter().any(|&s| s.abs() > 200) {
                any_non_zero = true;
                break;
            }
        }
        assert!(any_non_zero, "Expected decoded Opus loopback waveform to have non-zero energy");
    }

    #[test]
    fn smooth_soft_clip_is_continuous_at_knee_and_bounded() {
        let left = smooth_soft_clip(0.7199, 0.72, 0.98);
        let right = smooth_soft_clip(0.7201, 0.72, 0.98);
        assert!((right - left).abs() < 0.0005, "No cliff jump across knee");
        assert!(smooth_soft_clip(5.0, 0.72, 0.98) <= 0.98);
        assert!(smooth_soft_clip(-5.0, 0.72, 0.98) >= -0.98);
    }

    #[test]
    fn anti_plosive_hpf_attenuates_30hz_wind_blast_and_prevents_clipping() {
        let mut engine = RealtimeAudioEngine::new().expect("init audio engine");
        engine.mode = CaptureVoiceMode::Vad;
        engine.vad_threshold_db = -30.0;
        engine.anti_plosive_enabled = true;

        // Full-scale 30 Hz sub-bass wind blast at 0 dBFS
        let mut wind = [0i16; MONO_FRAME_SAMPLES];
        let mut idx = 0usize;
        for _ in 0..6 {
            for sample in wind.iter_mut() {
                let phase = 2.0 * std::f32::consts::PI * 30.0 * (idx as f32) / (SAMPLE_RATE_HZ as f32);
                *sample = (phase.sin() * 32767.0) as i16;
                idx += 1;
            }
            let res = engine.process_capture_pcm(&wind);
            // 30Hz full-scale blast should be heavily attenuated well below -20 dBFS
            assert!(res.input_db < -18.0, "Expected 30Hz wind blast attenuation, got {} dB", res.input_db);
            for &s in &engine.capture_float_buf {
                assert!(s.abs() <= 0.90, "Capture sample exceeded anti-clip ceiling: {s}");
            }
        }
    }

    #[test]
    fn blocked_afk_or_no_talk_power_never_transmits_but_measures_input_db() {
        let mut engine = RealtimeAudioEngine::new().expect("init audio engine");
        engine.mode = CaptureVoiceMode::Vad;
        engine.vad_threshold_db = -42.0;

        let mut speech = [0i16; MONO_FRAME_SAMPLES];
        for (n, sample) in speech.iter_mut().enumerate() {
            let phase = 2.0 * std::f32::consts::PI * 360.0 * (n as f32) / (SAMPLE_RATE_HZ as f32);
            *sample = (phase.sin() * 14000.0) as i16;
        }

        // 1. Server-side talk power restriction / AFK blocks green transmitting state while keeping dB meter alive
        engine.set_server_can_send_audio(false);
        let res_srv_blocked = engine.process_capture_pcm(&speech);
        assert!(!res_srv_blocked.is_transmitting);
        assert!(res_srv_blocked.outgoing_packet.is_none());
        assert!(res_srv_blocked.input_db > -20.0);

        // 2. Local AFK / mute block also prevents transmission even in Continuous or PTT mode
        engine.set_server_can_send_audio(true);
        engine.set_local_capture_blocked(true);
        engine.mode = CaptureVoiceMode::Continuous;
        let res_local_blocked = engine.process_capture_pcm(&speech);
        assert!(!res_local_blocked.is_transmitting);
        assert!(res_local_blocked.outgoing_packet.is_none());
        assert!(res_local_blocked.input_db > -20.0);

        // 3. Settings loopback test still works regardless of room block state
        engine.loopback_enabled = true;
        let res_loopback = engine.process_capture_pcm(&speech);
        assert!(res_loopback.is_transmitting);
    }

    #[test]
    fn speaker_change_detector_and_opus_music_codec_switching() {
        let mut engine = RealtimeAudioEngine::new().expect("init audio engine");
        assert_eq!(engine.take_changed_speakers(&[]), None);
        assert_eq!(engine.take_changed_speakers(&[12, 5, 12]), Some(vec![5, 12]));
        assert_eq!(engine.take_changed_speakers(&[5, 12]), None);
        assert_eq!(engine.take_changed_speakers(&[]), Some(vec![]));

        engine.set_channel_audio_format(5, 9);
        engine.mode = CaptureVoiceMode::Continuous;
        let silence = [0i16; MONO_FRAME_SAMPLES];
        let res = engine.process_capture_pcm(&silence);
        assert!(res.outgoing_packet.is_some());
    }
}
