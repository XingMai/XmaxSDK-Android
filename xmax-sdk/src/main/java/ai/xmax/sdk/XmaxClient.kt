package ai.xmax.sdk

/**
 * Root entry point for XmaxSDK.
 *
 * Realtime and storage factories will be added as their Android implementations
 * are ported. Constructing the client performs no network or media work.
 */
public class XmaxClient(
    public val configuration: XmaxConfiguration,
)

