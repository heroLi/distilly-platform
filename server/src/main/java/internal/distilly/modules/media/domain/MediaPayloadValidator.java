package internal.distilly.modules.media.domain;

import java.util.Base64;
import static internal.distilly.common.validation.InputChecks.error;
import static internal.distilly.common.validation.InputChecks.text;

public final class MediaPayloadValidator {

    private MediaPayloadValidator() {
    }

    public static String avatar(Object value) {
        String a = text(value, 1500000);
        if (!a.matches("data:image/(png|jpeg|webp);base64,[A-Za-z0-9+/=]+"))
            throw error(422, "invalid_avatar");
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(a.substring(a.indexOf(',') + 1));
        }
        catch (IllegalArgumentException e) {
            throw error(422, "invalid_avatar");
        }
        if (bytes.length > 1048576)
            throw error(413, "avatar_too_large");
        boolean valid = bytes.length > 12 && ((a.startsWith("data:image/png;") && bytes[0] == (byte) 137
                && bytes[1] == 80 && bytes[2] == 78 && bytes[3] == 71)
                || (a.startsWith("data:image/jpeg;") && bytes[0] == (byte) 255 && bytes[1] == (byte) 216
                        && bytes[2] == (byte) 255)
                || (a.startsWith("data:image/webp;")
                        && new String(bytes, 0, 4, java.nio.charset.StandardCharsets.US_ASCII).equals("RIFF")
                        && new String(bytes, 8, 4, java.nio.charset.StandardCharsets.US_ASCII).equals("WEBP")));
        if (!valid)
            throw error(422, "invalid_avatar_bytes");
        return a;
    }

    public static String audio(Object value) {
        String data = text(value, 1500000);
        if (!data.matches("data:audio/(mpeg|wav|webm|ogg|mp4);base64,[A-Za-z0-9+/=]+"))
            throw error(422, "invalid_audio");
        try {
            if (Base64.getDecoder().decode(data.substring(data.indexOf(',') + 1)).length > 1048576)
                throw error(413, "audio_too_large");
        }
        catch (IllegalArgumentException e) {
            throw error(422, "invalid_audio");
        }
        return data;
    }

}
