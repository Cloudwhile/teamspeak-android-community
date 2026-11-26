use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex};

use tsclientlib::Identity;

use crate::events::{is_en, IdentityInfoDto};

/// Generates a brand-new P-256 TeamSpeak Identity at default security level 8.
pub fn generate_identity() -> Result<IdentityInfoDto, String> {
    let mut id = Identity::create();
    if id.level() < 8 {
        id.upgrade_level(8);
    }
    Ok(identity_to_dto(&id))
}

/// Parses either a desktop TeamSpeak `.ini` file (`identity="12345V..."`) or a raw identity string.
pub fn parse_identity_any(raw_input: &str) -> Result<Identity, String> {
    let extracted = extract_identity_value(raw_input);
    if extracted.is_empty() {
        return Err(if is_en() {
            "Identity key content is empty".to_string()
        } else {
            "身份密钥内容为空".to_string()
        });
    }
    Identity::new_from_str(&extracted).map_err(|e| {
        if is_en() {
            format!("Failed to parse TeamSpeak identity: {e}")
        } else {
            format!("解析 TeamSpeak 身份失败: {e}")
        }
    })
}

pub fn inspect_identity_ini(raw_input: &str) -> Result<IdentityInfoDto, String> {
    let id = parse_identity_any(raw_input)?;
    Ok(identity_to_dto(&id))
}

/// Formats an [`Identity`] into a standard desktop TeamSpeak 3 `.ini` file payload.
pub fn export_identity_ini(id: &Identity) -> String {
    let obfuscated = id.key().to_ts_obfuscated();
    let token = format!("{}V{}", id.counter(), obfuscated);
    format!(
        "[Identity]\nid=TSCC\nidentity=\"{token}\"\nnickname=TSCC_Player\nphonetic_nickname=\n"
    )
}

pub fn identity_to_dto(id: &Identity) -> IdentityInfoDto {
    let pub_key = id.key().to_pub();
    IdentityInfoDto {
        ini_content: export_identity_ini(id),
        public_key_base64: pub_key.to_ts(),
        unique_id: pub_key.get_uid(),
        security_level: i32::from(id.level()),
        key_offset: id.counter() as i64,
        cancelled: false,
    }
}

/// Multi-core parallel SHA-1 Proof-of-Work hashcash upgrade for TeamSpeak Identity Security Level.
/// `on_progress` receives `(current_best_level, total_hashes_checked)`.
/// If cancelled midway after finding a higher intermediate level, preserves the upgraded counter
/// and returns `Ok(IdentityInfoDto { cancelled: true, .. })` so caller never loses earned PoW.
pub fn upgrade_security_level_parallel<F>(
    raw_input: &str,
    target_level: u8,
    cancel_flag: Arc<AtomicBool>,
    on_progress: F,
) -> Result<IdentityInfoDto, String>
where
    F: Fn(u8, u64) + Send + Sync + 'static,
{
    let mut identity = parse_identity_any(raw_input)?;
    let initial_level = identity.level();
    if initial_level >= target_level {
        return Ok(identity_to_dto(&identity));
    }

    cancel_flag.store(false, Ordering::SeqCst);
    let omega = Arc::new(identity.key().to_pub().to_ts());
    let start_counter = identity.max_counter().max(identity.counter()).saturating_add(1);

    let num_threads = std::thread::available_parallelism()
        .map(|n| n.get().clamp(1, 8))
        .unwrap_or(4);

    let found_done = Arc::new(AtomicBool::new(false));
    let best_state = Arc::new(Mutex::new((initial_level, identity.counter())));
    let total_checked = Arc::new(AtomicU64::new(0));
    let progress_cb = Arc::new(on_progress);

    let mut handles = Vec::with_capacity(num_threads);
    for worker_idx in 0..num_threads {
        let omega_cl = Arc::clone(&omega);
        let cancel_cl = Arc::clone(&cancel_flag);
        let done_cl = Arc::clone(&found_done);
        let best_cl = Arc::clone(&best_state);
        let checked_cl = Arc::clone(&total_checked);
        let cb_cl = Arc::clone(&progress_cb);

        handles.push(std::thread::spawn(move || {
            let mut offset = start_counter.saturating_add(worker_idx as u64);
            let stride = num_threads as u64;
            let mut local_iter: u64 = 0;

            while !done_cl.load(Ordering::Relaxed) && !cancel_cl.load(Ordering::Relaxed) {
                let lvl = tsproto::algorithms::get_hash_cash_level(&omega_cl, offset);
                if lvl > initial_level {
                    let mut guard = best_cl.lock().unwrap();
                    if lvl > guard.0 {
                        *guard = (lvl, offset);
                        let checked_now = checked_cl.load(Ordering::Relaxed).saturating_add(local_iter & 0x3FFF);
                        cb_cl(lvl, checked_now);
                    }
                    if lvl >= target_level {
                        done_cl.store(true, Ordering::SeqCst);
                        break;
                    }
                }

                local_iter += 1;
                if local_iter & 0x3FFF == 0 {
                    let total = checked_cl.fetch_add(0x4000, Ordering::Relaxed) + 0x4000;
                    if worker_idx == 0 {
                        let cur_best = best_cl.lock().unwrap().0;
                        cb_cl(cur_best, total);
                    }
                }

                match offset.checked_add(stride) {
                    Some(next) => offset = next,
                    None => break,
                }
            }
        }));
    }

    for h in handles {
        let _ = h.join();
    }

    let (best_lvl, best_offset) = *best_state.lock().unwrap();
    if best_lvl > initial_level {
        identity.set_counter(best_offset);
        identity.set_max_counter(best_offset);
    }

    let was_cancelled = cancel_flag.load(Ordering::Relaxed) && best_lvl < target_level;
    let mut dto = identity_to_dto(&identity);
    dto.cancelled = was_cancelled;
    Ok(dto)
}

fn extract_identity_value(raw: &str) -> String {
    let trimmed = raw.trim();
    for line in trimmed.lines() {
        let l = line.trim();
        if let Some(rest) = l.strip_prefix("identity=") {
            let unquoted = rest.trim().trim_matches('"').trim_matches('\'').trim();
            // Unescape Qt/INI escaped backslashes or quotes if any
            return unquoted.replace("\\\"", "\"").replace("\\\\", "\\");
        }
    }
    trimmed.trim_matches('"').replace(['\r', '\n', ' ', '\t'], "")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn generate_export_import_and_upgrade_roundtrip() {
        let generated = generate_identity().expect("generate identity");
        assert!(generated.security_level >= 8);
        assert!(!generated.unique_id.is_empty());

        let reimported = inspect_identity_ini(&generated.ini_content).expect("reimport ini");
        assert_eq!(generated.unique_id, reimported.unique_id);
        assert_eq!(generated.security_level, reimported.security_level);

        let cancel = Arc::new(AtomicBool::new(false));
        let target = (generated.security_level as u8 + 2).min(14);
        let upgraded = upgrade_security_level_parallel(&generated.ini_content, target, cancel, |_, _| {})
            .expect("upgrade level");
        assert!(upgraded.security_level >= i32::from(target));
        assert_eq!(generated.unique_id, upgraded.unique_id);
    }
}
