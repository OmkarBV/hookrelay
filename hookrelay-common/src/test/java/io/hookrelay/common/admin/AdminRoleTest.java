package io.hookrelay.common.admin;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AdminRoleTest {

    @Test
    void ownerHasEveryPermission() {
        assertThat(AdminRole.OWNER.permissions())
                .containsExactlyInAnyOrder(Permission.ENDPOINT_CREATE, Permission.DELIVERY_REPLAY, Permission.SECRET_ROTATE);
    }

    @Test
    void developerCanOperateEndpointsAndSecretsButNotReplayDeliveries() {
        assertThat(AdminRole.DEVELOPER.permissions())
                .containsExactlyInAnyOrder(Permission.ENDPOINT_CREATE, Permission.SECRET_ROTATE)
                .doesNotContain(Permission.DELIVERY_REPLAY);
    }

    @Test
    void viewerHasNoMutatingPermissions() {
        assertThat(AdminRole.VIEWER.permissions()).isEmpty();
    }
}
