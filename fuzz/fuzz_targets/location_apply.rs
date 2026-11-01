#![no_main]

libfuzzer_sys::fuzz_target!(|data: &[u8]| pixelgrove::fuzz::location_apply(data));
