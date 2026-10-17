package ru.moonlightproject.dev.selene.paper;

final class LimboLockPolicy {
    private LimboLockPolicy() {}

    static boolean locked(AuthStage stage, boolean managementActive) {
        return managementActive || stage != AuthStage.AUTHENTICATED;
    }
}
