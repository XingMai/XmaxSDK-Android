package ai.xmax.sdk

/** 实时流程发生异步错误时触发的监听器。 */
public fun interface RealtimeErrorListener {
    public fun onError(error: XmaxError)
}
