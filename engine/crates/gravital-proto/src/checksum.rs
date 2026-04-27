/// RFC 1071 Internet checksum — one's complement sum of 16-bit words.
#[must_use]
pub fn internet_checksum(data: &[u8]) -> u16 {
    let mut sum: u32 = 0;
    let mut chunks = data.chunks_exact(2);

    for chunk in chunks.by_ref() {
        sum += u32::from(u16::from_be_bytes([chunk[0], chunk[1]]));
    }

    if let Some(&odd) = chunks.remainder().first() {
        sum += u32::from(u16::from_be_bytes([odd, 0]));
    }

    while sum >> 16 != 0 {
        sum = (sum & 0xFFFF) + (sum >> 16);
    }

    !(sum as u16)
}

/// Pseudo-header checksum for TCP/UDP over IPv4.
#[must_use]
pub fn pseudo_header_v4(src: [u8; 4], dst: [u8; 4], proto: u8, len: u16) -> u32 {
    let mut sum: u32 = 0;
    sum += u32::from(u16::from_be_bytes([src[0], src[1]]));
    sum += u32::from(u16::from_be_bytes([src[2], src[3]]));
    sum += u32::from(u16::from_be_bytes([dst[0], dst[1]]));
    sum += u32::from(u16::from_be_bytes([dst[2], dst[3]]));
    sum += u32::from(u16::from_be_bytes([0, proto]));
    sum += u32::from(len);
    sum
}

/// Fold a running checksum accumulator into a u16.
#[must_use]
pub fn fold(mut acc: u32) -> u16 {
    while acc >> 16 != 0 {
        acc = (acc & 0xFFFF) + (acc >> 16);
    }
    !(acc as u16)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn known_vector() {
        // 0x0001 + 0xf203 + 0xf4f5 + 0xf6f7 → folded sum = 0xddf2 → ~0xddf2 = 0x220d
        let data = [0x00u8, 0x01, 0xf2, 0x03, 0xf4, 0xf5, 0xf6, 0xf7];
        assert_eq!(internet_checksum(&data), 0x220d);
    }

    #[test]
    fn all_zeros() {
        let data = [0u8; 20];
        assert_eq!(internet_checksum(&data), 0xffff);
    }
}
