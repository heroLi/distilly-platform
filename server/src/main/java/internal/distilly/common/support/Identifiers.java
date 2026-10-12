package internal.distilly.common.support;

import java.util.UUID;

public final class Identifiers {

    private Identifiers() {
    }

    public static String id() {
        return UUID.randomUUID().toString();
    }

    public static long now() {
        return System.currentTimeMillis();
    }

}
