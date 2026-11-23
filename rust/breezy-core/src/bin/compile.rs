//! Host-side filter list compiler used by the Gradle build.
//!
//! `breezy-compile <out.dat> [+]<list.txt>...`  (a `+` prefix marks a trusted list)
//!
//! Compiles the given ABP/uBO filter lists into adblock-rust's serialized engine format so the
//! app can load a ready-made engine from its assets in milliseconds on first launch instead of
//! parsing megabytes of filter text on the device.

use std::{env, fs, process::ExitCode, time::Instant};

fn main() -> ExitCode {
    let args: Vec<String> = env::args().skip(1).collect();
    let Some((out, lists)) = args.split_first() else {
        eprintln!("usage: breezy-compile <out.dat> <list.txt>...");
        return ExitCode::from(2);
    };
    let mut texts = Vec::new();
    for arg in lists {
        let (path, trusted) = match arg.strip_prefix('+') { Some(p) => (p, true), None => (arg.as_str(), false) };
        match fs::read_to_string(path) {
            Ok(t) => texts.push((t, trusted)),
            Err(e) => {
                eprintln!("breezy-compile: cannot read {path}: {e}");
                return ExitCode::FAILURE;
            }
        }
    }
    let start = Instant::now();
    breezy_core::load_lists_trusted(texts.iter().map(|(t, trusted)| (t.as_str(), *trusted)));
    let data = breezy_core::serialize_current();
    if let Err(e) = fs::write(out, &data) {
        eprintln!("breezy-compile: cannot write {out}: {e}");
        return ExitCode::FAILURE;
    }
    println!(
        "breezy-compile: {} lists -> {out} ({} KiB) in {:?}",
        lists.len(),
        data.len() / 1024,
        start.elapsed()
    );
    ExitCode::SUCCESS
}
