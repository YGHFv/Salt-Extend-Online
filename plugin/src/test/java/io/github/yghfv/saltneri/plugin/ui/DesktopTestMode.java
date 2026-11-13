package io.github.yghfv.saltneri.plugin.ui;

/** Screen sampling and Robot input require an explicitly requested local test session. */
final class DesktopTestMode {
    private DesktopTestMode() { }
    static boolean interactive() {
        return Boolean.getBoolean("salt.test.interactiveDesktop")
                && !"true".equalsIgnoreCase(System.getenv("CI"))
                && !"true".equalsIgnoreCase(System.getenv("GITHUB_ACTIONS"));
    }
}
