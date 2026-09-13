package io.hookrelay.common.admin;

import java.util.EnumSet;
import java.util.Set;

import static io.hookrelay.common.admin.Permission.DELIVERY_REPLAY;
import static io.hookrelay.common.admin.Permission.ENDPOINT_CREATE;
import static io.hookrelay.common.admin.Permission.SECRET_ROTATE;

/**
 * The role-to-permission mapping is fixed in code rather than stored in the
 * database: three roles and three permissions is a closed, small set with no
 * requirement for operators to define custom roles, so a configurable
 * many-to-many RBAC table would be unused complexity. If per-tenant custom
 * roles are ever needed, this is the seam to replace.
 */
public enum AdminRole {
    OWNER(EnumSet.of(ENDPOINT_CREATE, DELIVERY_REPLAY, SECRET_ROTATE)),
    DEVELOPER(EnumSet.of(ENDPOINT_CREATE, SECRET_ROTATE)),
    VIEWER(EnumSet.noneOf(Permission.class));

    private final Set<Permission> permissions;

    AdminRole(Set<Permission> permissions) {
        this.permissions = permissions;
    }

    public Set<Permission> permissions() {
        return permissions;
    }
}
