package io.hookrelay.common.admin;

/** Fine-grained admin authorities, granted to roles via {@link AdminRole#permissions()}. */
public enum Permission {
    ENDPOINT_CREATE,
    DELIVERY_REPLAY,
    SECRET_ROTATE
}
