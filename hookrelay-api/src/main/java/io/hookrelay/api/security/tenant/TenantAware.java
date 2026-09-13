package io.hookrelay.api.security.tenant;

import java.util.UUID;

/** Implemented by every authentication principal Hookrelay issues (API key or admin JWT). */
public interface TenantAware {

    UUID getTenantId();
}
