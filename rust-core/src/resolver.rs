use std::collections::HashMap;
use std::net::{IpAddr, Ipv4Addr, SocketAddr};
use std::sync::{Arc, Mutex, OnceLock};
use std::time::{Duration, Instant};

use futures::FutureExt;
use hickory_net::proto::rr::RData;
use hickory_resolver::config::{
    ConnectionConfig, NameServerConfig, ResolverConfig, ResolverOpts,
};
use hickory_resolver::net::runtime::TokioRuntimeProvider;
use hickory_resolver::TokioResolver;
use tokio::io::{AsyncBufReadExt, AsyncReadExt, AsyncWriteExt, BufReader};
use tokio::net::{lookup_host, TcpStream};

use crate::events::is_en;

const DEFAULT_TS_PORT: u16 = 9987;
const DNS_CACHE_TTL: Duration = Duration::from_secs(120);

struct CachedDnsEntry {
    addrs: Vec<SocketAddr>,
    expires_at: Instant,
}

#[derive(Default)]
struct ResolverState {
    last_dns_csv: String,
    resolver: Option<Arc<TokioResolver>>,
    entries: HashMap<(String, u16), CachedDnsEntry>,
}

static RESOLVER_CACHE: OnceLock<Mutex<ResolverState>> = OnceLock::new();

fn cache_state() -> &'static Mutex<ResolverState> {
    RESOLVER_CACHE.get_or_init(|| Mutex::new(ResolverState::default()))
}

pub fn invalidate_dns_cache(raw_host: &str, port: u16) {
    let host = normalize_host(raw_host);
    if host.is_empty() {
        return;
    }
    let effective_port = if port == 0 { DEFAULT_TS_PORT } else { port };
    if let Ok(mut guard) = cache_state().lock() {
        guard.entries.remove(&(host.to_ascii_lowercase(), effective_port));
    }
}

fn normalize_host(raw_host: &str) -> &str {
    raw_host
        .trim()
        .trim_start_matches('[')
        .trim_end_matches(']')
        .trim_end_matches('.')
}

/// Low-latency concurrent TeamSpeak server resolver (`_ts3._udp` SRV, A/AAAA Happy-Eyeballs, and `_tsdns._tcp`).
/// - Reuses a cached `TokioResolver` and 120s TTL result cache for near-instant reconnects.
/// - Races Hickory DNS and OS Bionic `lookup_host` in parallel rather than sequential waterfall waits.
/// - Runs `_ts3._udp` SRV concurrently with A/AAAA on port 9987 with a short 220ms grace cap once A/AAAA succeeds.
pub async fn resolve_teamspeak_server(
    raw_host: &str,
    port: u16,
    dns_servers_csv: &str,
) -> Result<Vec<SocketAddr>, String> {
    let host = normalize_host(raw_host);
    if host.is_empty() {
        return Err(if is_en() {
            "Server address cannot be empty".to_string()
        } else {
            "服务器地址不能为空".to_string()
        });
    }

    let effective_port = if port == 0 { DEFAULT_TS_PORT } else { port };

    // 1. Direct IPv4 / IPv6 literal fast path (0ms)
    if let Ok(ip) = host.parse::<IpAddr>() {
        return Ok(vec![SocketAddr::new(ip, effective_port)]);
    }

    let cache_key = (host.to_ascii_lowercase(), effective_port);
    let resolver = {
        let mut guard = cache_state()
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        let now = Instant::now();
        if let Some(hit) = guard.entries.get(&cache_key) {
            if hit.expires_at > now && !hit.addrs.is_empty() {
                return Ok(hit.addrs.clone());
            }
        }
        if guard.resolver.is_none() || guard.last_dns_csv != dns_servers_csv {
            guard.resolver = build_android_safe_resolver(dns_servers_csv).map(Arc::new);
            guard.last_dns_csv = dns_servers_csv.to_string();
        }
        guard.resolver.clone()
    };

    let mut candidates = Vec::new();
    let mut cacheable = true;

    if effective_port == DEFAULT_TS_PORT {
        if let Some(ref res) = resolver {
            // Run _ts3._udp SRV, _tsdns._tcp TSDNS, and A/AAAA concurrently,
            // Assemble results in standard TeamSpeak resolution priority order:
            //   1. _ts3._udp SRV   2. _tsdns._tcp TSDNS   3. A/AAAA (:9987)
            // When NXDOMAIN is returned for SRV/TSDNS (common case), Hickory resolves in ~20-60ms.
            // On high-latency cellular networks where A/AAAA is cached locally, allow up to 750ms
            // for SRV/TSDNS so custom ports on web-backed domains are never prematurely dropped.
            let srv_fut = lookup_ts3_srv(res, host).fuse();
            let tsdns_fut = lookup_tsdns(res, host).fuse();
            let direct_fut = resolve_host_ips_fast(Some(res), host, effective_port).fuse();
            futures::pin_mut!(srv_fut, tsdns_fut, direct_fut);

            let mut srv_addrs: Option<Vec<SocketAddr>> = None;
            let mut tsdns_addrs: Option<Vec<SocketAddr>> = None;
            let mut direct_addrs: Option<Result<Vec<SocketAddr>, String>> = None;

            tokio::select! {
                s = &mut srv_fut => {
                    srv_addrs = Some(s);
                }
                d = &mut direct_fut => {
                    direct_addrs = Some(d);
                }
            }

            if let Some(ref s) = srv_addrs {
                if !s.is_empty() {
                    // Got authoritative _ts3._udp SRV! Briefly gather A/AAAA as last-resort fallback only
                    if let Ok(Ok(d)) = tokio::time::timeout(Duration::from_millis(80), &mut direct_fut).await {
                        direct_addrs = Some(Ok(d));
                    }
                } else {
                    // No _ts3._udp SRV -> wait for TSDNS and A/AAAA
                    let (t, d_opt) = futures::future::join(
                        tokio::time::timeout(Duration::from_millis(900), &mut tsdns_fut),
                        async {
                            match direct_addrs.take() {
                                Some(done) => done,
                                None => direct_fut.await,
                            }
                        },
                    )
                    .await;
                    if let Ok(t_vec) = t {
                        tsdns_addrs = Some(t_vec);
                    } else {
                        cacheable = false;
                    }
                    direct_addrs = Some(d_opt);
                }
            } else {
                // direct_fut finished first: give SRV + TSDNS up to 750ms (returns immediately on NXDOMAIN)
                let (s_res, t_res) = futures::future::join(
                    tokio::time::timeout(Duration::from_millis(750), &mut srv_fut),
                    tokio::time::timeout(Duration::from_millis(850), &mut tsdns_fut),
                )
                .await;
                match s_res {
                    Ok(s_vec) => srv_addrs = Some(s_vec),
                    Err(_) => cacheable = false,
                }
                match t_res {
                    Ok(t_vec) => tsdns_addrs = Some(t_vec),
                    Err(_) => cacheable = false,
                }
                // If direct A/AAAA failed, wait fully for SRV & TSDNS
                if direct_addrs.as_ref().map(|r| r.is_err()).unwrap_or(true) {
                    if srv_addrs.is_none() {
                        srv_addrs = Some(srv_fut.await);
                    }
                    if tsdns_addrs.is_none() {
                        tsdns_addrs = Some(tsdns_fut.await);
                    }
                }
            }

            if let Some(s) = srv_addrs {
                append_unique(&mut candidates, s);
            }
            if let Some(t) = tsdns_addrs {
                append_unique(&mut candidates, t);
            }
            if let Some(Ok(d)) = direct_addrs {
                append_unique(&mut candidates, d);
            }
        } else if let Ok(os_addrs) = resolve_host_ips_fast(None, host, effective_port).await {
            append_unique(&mut candidates, os_addrs);
        }
    } else if let Ok(direct_addrs) =
        resolve_host_ips_fast(resolver.as_deref(), host, effective_port).await
    {
        append_unique(&mut candidates, direct_addrs);
    }

    if candidates.is_empty() {
        Err(if is_en() {
            format!("Unable to resolve server hostname: {host}")
        } else {
            format!("无法解析服务器域名: {host}")
        })
    } else {
        if cacheable {
            if let Ok(mut guard) = cache_state().lock() {
                guard.entries.insert(
                    cache_key,
                    CachedDnsEntry {
                        addrs: candidates.clone(),
                        expires_at: Instant::now() + DNS_CACHE_TTL,
                    },
                );
            }
        }
        Ok(candidates)
    }
}

fn append_unique(dest: &mut Vec<SocketAddr>, mut incoming: Vec<SocketAddr>) {
    // Prefer IPv4 ahead of IPv6 while preserving relative SRV priority order
    incoming.sort_by_key(|a| a.is_ipv6());
    for addr in incoming {
        if !dest.contains(&addr) {
            dest.push(addr);
        }
    }
}

async fn lookup_ts3_srv(resolver: &TokioResolver, host: &str) -> Vec<SocketAddr> {
    let srv_query = format!("_ts3._udp.{host}.");
    let Ok(Ok(srv_lookup)) =
        tokio::time::timeout(Duration::from_millis(1200), resolver.srv_lookup(srv_query)).await
    else {
        return Vec::new();
    };

    let mut records: Vec<_> = srv_lookup
        .answers()
        .iter()
        .filter_map(|r| match &r.data {
            RData::SRV(srv) => Some(srv.clone()),
            _ => None,
        })
        .collect();
    records.sort_by_key(|r| (r.priority, std::cmp::Reverse(r.weight)));

    let mut out = Vec::new();
    for srv in records {
        let target_host = srv.target.to_ascii();
        let target_clean = target_host.trim_end_matches('.');
        if target_clean.is_empty() {
            continue;
        }
        if let Ok(ips) = resolve_host_ips_fast(Some(resolver), target_clean, srv.port).await {
            append_unique(&mut out, ips);
        }
    }
    out
}

async fn lookup_tsdns(resolver: &TokioResolver, host: &str) -> Vec<SocketAddr> {
    for candidate_zone in candidate_tsdns_zones(host) {
        let tsdns_srv = format!("_tsdns._tcp.{}.", candidate_zone.trim_end_matches('.'));
        let Ok(Ok(srv_lookup)) =
            tokio::time::timeout(Duration::from_millis(1000), resolver.srv_lookup(tsdns_srv)).await
        else {
            continue;
        };

        let mut srvs: Vec<_> = srv_lookup
            .answers()
            .iter()
            .filter_map(|r| match &r.data {
                RData::SRV(srv) => Some(srv.clone()),
                _ => None,
            })
            .collect();
        srvs.sort_by_key(|r| (r.priority, std::cmp::Reverse(r.weight)));

        for srv in srvs {
            let tsdns_target = srv.target.to_ascii();
            let tsdns_clean = tsdns_target.trim_end_matches('.');
            if tsdns_clean.is_empty() {
                continue;
            }
            if let Ok(tsdns_addrs) =
                resolve_host_ips_fast(Some(resolver), tsdns_clean, srv.port).await
            {
                for tsdns_addr in tsdns_addrs {
                    if let Ok(mapped) = query_tsdns_server(tsdns_addr, host).await {
                        return vec![mapped];
                    }
                }
            }
        }
    }
    Vec::new()
}

async fn resolve_host_ips_fast(
    resolver: Option<&TokioResolver>,
    host: &str,
    port: u16,
) -> Result<Vec<SocketAddr>, String> {
    if let Ok(ip) = host.parse::<IpAddr>() {
        return Ok(vec![SocketAddr::new(ip, port)]);
    }

    let os_fut = async move {
        let iter = tokio::time::timeout(Duration::from_millis(1600), lookup_host((host, port)))
            .await
            .map_err(|_| "OS DNS timeout".to_string())?
            .map_err(|e| e.to_string())?;
        let mut list: Vec<SocketAddr> = iter.collect();
        list.sort_by_key(|a| a.is_ipv6());
        if list.is_empty() {
            Err("Empty OS DNS result".to_string())
        } else {
            Ok(list)
        }
    };

    if let Some(res) = resolver {
        let hickory_fut = async move {
            let lookup = tokio::time::timeout(Duration::from_millis(1400), res.lookup_ip(host))
                .await
                .map_err(|_| "Hickory DNS timeout".to_string())?
                .map_err(|e| e.to_string())?;
            let mut list: Vec<SocketAddr> =
                lookup.iter().map(|ip| SocketAddr::new(ip, port)).collect();
            list.sort_by_key(|a| a.is_ipv6());
            if list.is_empty() {
                Err("Empty Hickory DNS result".to_string())
            } else {
                Ok(list)
            }
        };

        futures::future::select_ok([hickory_fut.boxed(), os_fut.boxed()])
            .await
            .map(|(addrs, _)| addrs)
    } else {
        os_fut.await
    }
}

async fn query_tsdns_server(
    tsdns_addr: SocketAddr,
    lookup_name: &str,
) -> Result<SocketAddr, String> {
    let mut stream =
        tokio::time::timeout(Duration::from_millis(1200), TcpStream::connect(tsdns_addr))
            .await
            .map_err(|_| "TSDNS timeout".to_string())?
            .map_err(|e| e.to_string())?;

    stream
        .write_all(format!("{lookup_name}\r\n").as_bytes())
        .await
        .map_err(|e| e.to_string())?;
    stream.flush().await.map_err(|e| e.to_string())?;

    let mut line = String::with_capacity(128);
    let mut reader = BufReader::new(stream.take(512));
    tokio::time::timeout(Duration::from_millis(1200), reader.read_line(&mut line))
        .await
        .map_err(|_| "TSDNS read timeout".to_string())?
        .map_err(|e| e.to_string())?;

    parse_tsdns_response(&line)
}

fn parse_tsdns_response(raw_resp: &str) -> Result<SocketAddr, String> {
    let resp = raw_resp.lines().next().unwrap_or("").trim();
    if resp.is_empty() || resp == "404" || resp.eq_ignore_ascii_case("NORESULT") {
        return Err("TSDNS 404".to_string());
    }
    if let Some(without_default) = resp.strip_suffix(":$PORT") {
        let unbracketed = without_default
            .trim()
            .trim_start_matches('[')
            .trim_end_matches(']');
        let ip: IpAddr = unbracketed
            .parse()
            .map_err(|_| "Invalid TSDNS IP".to_string())?;
        return Ok(SocketAddr::new(ip, DEFAULT_TS_PORT));
    }
    resp.parse::<SocketAddr>()
        .map_err(|_| format!("Invalid TSDNS response: {resp}"))
}

fn build_android_safe_resolver(dns_servers_csv: &str) -> Option<TokioResolver> {
    let mut ips: Vec<IpAddr> = dns_servers_csv
        .split(',')
        .filter_map(|s| s.trim().parse::<IpAddr>().ok())
        .collect();

    let fallbacks = [
        IpAddr::V4(Ipv4Addr::new(223, 5, 5, 5)),
        IpAddr::V4(Ipv4Addr::new(119, 29, 29, 29)),
        IpAddr::V4(Ipv4Addr::new(1, 1, 1, 1)),
        IpAddr::V4(Ipv4Addr::new(8, 8, 8, 8)),
    ];
    for fb in fallbacks {
        if !ips.contains(&fb) {
            ips.push(fb);
        }
    }

    let name_servers: Vec<NameServerConfig> = ips
        .into_iter()
        .map(|ip| {
            NameServerConfig::new(
                ip,
                true,
                vec![ConnectionConfig::udp(), ConnectionConfig::tcp()],
            )
        })
        .collect();

    let config = ResolverConfig::from_name_servers(name_servers);
    let mut opts = ResolverOpts::default();
    opts.timeout = Duration::from_millis(1000);
    opts.attempts = 2;

    let mut builder = TokioResolver::builder_with_config(config, TokioRuntimeProvider::default());
    *builder.options_mut() = opts;
    builder.build().ok()
}

fn is_multi_part_public_suffix(two_label_tail: &str) -> bool {
    matches!(
        two_label_tail.to_ascii_lowercase().as_str(),
        "com.cn"
            | "net.cn"
            | "org.cn"
            | "gov.cn"
            | "edu.cn"
            | "ac.cn"
            | "co.uk"
            | "org.uk"
            | "ac.uk"
            | "gov.uk"
            | "com.au"
            | "net.au"
            | "org.au"
            | "co.jp"
            | "ne.jp"
            | "or.jp"
            | "com.hk"
            | "org.hk"
            | "edu.hk"
            | "com.tw"
            | "org.tw"
            | "idv.tw"
            | "com.sg"
            | "co.kr"
            | "or.kr"
            | "com.br"
            | "com.ru"
            | "co.nz"
    )
}

fn extract_root_domain(host: &str) -> &str {
    let clean = host.trim_end_matches('.');
    let parts: Vec<&str> = clean.split('.').filter(|s| !s.is_empty()).collect();
    if parts.len() <= 2 {
        return clean;
    }
    let tail_two = format!("{}.{}", parts[parts.len() - 2], parts[parts.len() - 1]);
    let keep_labels = if is_multi_part_public_suffix(&tail_two) {
        3
    } else {
        2
    };
    if parts.len() <= keep_labels {
        return clean;
    }
    // Slice exact suffix from `clean` corresponding to the last `keep_labels` labels
    let mut dot_pos = clean.len();
    for _ in 0..keep_labels {
        if let Some(idx) = clean[..dot_pos].rfind('.') {
            dot_pos = idx;
        } else {
            return clean;
        }
    }
    &clean[dot_pos + 1..]
}

fn candidate_tsdns_zones(host: &str) -> Vec<&str> {
    let clean = host.trim_end_matches('.');
    let root = extract_root_domain(clean);
    let mut zones = vec![root];
    if clean != root {
        zones.push(clean);
    }
    zones
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn resolves_ipv4_and_ipv6_literals_directly() {
        let v4 = resolve_teamspeak_server("192.168.1.50", 9988, "")
            .await
            .expect("v4 literal");
        assert_eq!(v4, vec!["192.168.1.50:9988".parse::<SocketAddr>().unwrap()]);

        let v6 = resolve_teamspeak_server("[2001:db8::1]", 9987, "")
            .await
            .expect("v6 literal");
        assert_eq!(v6, vec!["[2001:db8::1]:9987".parse::<SocketAddr>().unwrap()]);
    }

    #[test]
    fn sorts_ipv4_before_ipv6_and_deduplicates() {
        let mut dest = Vec::new();
        append_unique(
            &mut dest,
            vec![
                "[2001:db8::1]:9987".parse().unwrap(),
                "203.0.113.10:9987".parse().unwrap(),
                "203.0.113.10:9987".parse().unwrap(),
            ],
        );
        assert_eq!(dest.len(), 2);
        assert!(dest[0].is_ipv4());
        assert!(dest[1].is_ipv6());
    }

    #[test]
    fn extracts_two_label_and_multipart_tld_root_domain_for_tsdns() {
        assert_eq!(extract_root_domain("voice.eu.clan.org"), "clan.org");
        assert_eq!(extract_root_domain("clan.org"), "clan.org");
        assert_eq!(extract_root_domain("ts.gaming.com.cn"), "gaming.com.cn");
        assert_eq!(extract_root_domain("voice.guild.co.uk"), "guild.co.uk");
    }

    #[test]
    fn parses_tsdns_ipv4_and_bracketed_ipv6_default_port() {
        assert_eq!(
            parse_tsdns_response("203.0.113.9:$PORT\r\n").unwrap(),
            "203.0.113.9:9987".parse::<SocketAddr>().unwrap()
        );
        assert_eq!(
            parse_tsdns_response("[2001:db8::9]:$PORT\r\n").unwrap(),
            "[2001:db8::9]:9987".parse::<SocketAddr>().unwrap()
        );
        assert_eq!(
            parse_tsdns_response("[2001:db8::9]:10055\r\n").unwrap(),
            "[2001:db8::9]:10055".parse::<SocketAddr>().unwrap()
        );
        assert!(parse_tsdns_response("404\r\n").is_err());
    }
}
