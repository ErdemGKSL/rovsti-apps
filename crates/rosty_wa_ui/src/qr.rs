use image::{ImageEncoder, Luma, codecs::png::PngEncoder};
use qrcode::QrCode;

#[derive(Debug, thiserror::Error)]
pub enum QrError {
    #[error("QR payload does not fit in a QR code: {0}")]
    Encode(#[from] qrcode::types::QrError),
    #[error("PNG encoding failed: {0}")]
    Png(#[from] image::ImageError),
}

/// Renders a pairing payload as a black-on-white PNG, at least `min_px`
/// square, with the standard 4-module quiet zone so phone cameras lock on
/// quickly even from a monitor.
pub fn qr_png(payload: &str, min_px: u32) -> Result<Vec<u8>, QrError> {
    let code = QrCode::new(payload.as_bytes())?;
    let image = code
        .render::<Luma<u8>>()
        .quiet_zone(true)
        .min_dimensions(min_px, min_px)
        .build();
    let mut png = Vec::new();
    PngEncoder::new(&mut png).write_image(
        image.as_raw(),
        image.width(),
        image.height(),
        image::ExtendedColorType::L8,
    )?;
    Ok(png)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn renders_a_png() {
        // Shape of a real pairing payload: ref,noise-pub,identity-pub,adv-secret.
        let payload = "2@AbCdEf0123456789,Zm9vYmFyYmF6cXV4,cXV4YmF6YmFyZm9v,ZGVhZGJlZWY=";
        let png = qr_png(payload, 512).expect("renders");
        assert_eq!(&png[..8], b"\x89PNG\r\n\x1a\n");
        let decoded = image::load_from_memory(&png).expect("valid png");
        assert!(decoded.width() >= 512);
        assert_eq!(decoded.width(), decoded.height());
    }
}
