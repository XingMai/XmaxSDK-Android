package com.xmax.xlab.modules.xlrealtime

internal enum class ReferenceInput {
    REFERENCES,
    INSTRUCTION,
    PROMPT,
}

internal data class ReferenceItem(
    val id: String,
    val categoryId: String,
    val title: String,
    val iconUrl: String,
    val defaultReferenceUrl: String,
) {
    val prompt: String
        get() = promptForReferenceCategory(categoryId)
}

internal fun promptForReferenceCategory(categoryId: String): String = when (categoryId) {
    "charx" -> "视频中角色替换成参考图中角色"
    "clothx" -> "视频中人物衣服替换成参考图中衣服"
    "vibex" -> "视频风格变为参考图指定的风格"
    "dimx" -> "指定角色在场景中互动"
    else -> ""
}

internal data class ReferenceCategory(
    val id: String,
    val name: String,
    val input: ReferenceInput,
    val instruction: String = "",
    val references: List<ReferenceItem> = emptyList(),
)

internal val realtimeReferenceCategories: List<ReferenceCategory> by lazy { listOf(
    ReferenceCategory("charx", "换形象", ReferenceInput.REFERENCES, references = referencesFor("charx")),
    ReferenceCategory("clothx", "换装", ReferenceInput.REFERENCES, references = referencesFor("clothx")),
    ReferenceCategory("vibex", "换风格", ReferenceInput.REFERENCES, references = referencesFor("vibex")),
    ReferenceCategory("dimx", "虚拟召唤", ReferenceInput.REFERENCES, references = referencesFor("dimx")),
    ReferenceCategory(
        id = "mox",
        name = "触控动图",
        input = ReferenceInput.INSTRUCTION,
        instruction = "在画面上拖拽，用轨迹控制角色",
    ),
    ReferenceCategory("free", "自由", ReferenceInput.PROMPT),
) }

private fun referencesFor(categoryId: String): List<ReferenceItem> = realtimeReferenceItems.filter {
    it.categoryId == categoryId
}

// Snapshot of POST https://snapo.xmaxai.com/xlive/api/v1/gameplay/list, generated 2026-08-17.
private val realtimeReferenceItems: List<ReferenceItem> = listOf(
    ReferenceItem("xgp-53f286ca852e494c", "charx", "奶龙", "https://assets.ducktracks.fun/xlive/gameplay/feishu/charx/UOOdbmssxobSxox6sPGcpIRjndd.png", "https://assets.ducktracks.fun/xlive/gameplay/feishu/charx/HjBSbgbjtoEMzzxLi5jc6iq9nMe.png"),
    ReferenceItem("xgp-edacd6760a584092", "charx", "喜多川海梦", "https://assets.ducktracks.fun/xlive/gameplay/feishu/charx/Wlqcba19korj2zxQZl0cQpJRnhD.png", "https://assets.ducktracks.fun/xlive/gameplay/feishu/charx/BlSXbbvUpoVvBoxo2KDcoS2nnxh.png"),
    ReferenceItem("xgp-90545d73d5a04111", "charx", "黄昏", "https://assets.ducktracks.fun/xlive/gameplay/feishu/charx/TCvFbZQASokaU8xJMzkcQUv8nXb.png", "https://assets.ducktracks.fun/xlive/gameplay/feishu/charx/DRfmb3AJBof693xeVRhczhQ6ntb.png"),
    ReferenceItem("xgp-92710eb588ac4379", "charx", "卡布奇诺", "https://assets.ducktracks.fun/xlive/gameplay/feishu/charx/W2ZvbzQRyoEqgrxnpLLcO1iVnS5.png", "https://assets.ducktracks.fun/xlive/gameplay/feishu/charx/Na42b2qDfoslzexy553cqvZRn8B.png"),
    ReferenceItem("xgp-d5ee46e4b02641de", "charx", "蜜璃", "https://assets.ducktracks.fun/xlive/gameplay/feishu/charx/E6ncb9SASo3dmcxPLnGcitUPn2c.png", "https://assets.ducktracks.fun/xlive/gameplay/feishu/charx/H0S9bphjUoKKRexnrlfcwheYnAg.png"),
    ReferenceItem("xgp-ffd9de4265a44c5b", "charx", "黑猫诺尔", "https://assets.ducktracks.fun/xlive/gameplay/feishu/charx/ViKbbwYJfocTu9x7i6IcY5VvnZc.png", "https://assets.ducktracks.fun/xlive/gameplay/feishu/charx/NDlgbi7bioqMSTxbPRbcXzRanOp.png"),
    ReferenceItem("xgp-8bd669b27451455c", "charx", "绫濑桃", "https://assets.ducktracks.fun/xlive/gameplay/feishu/charx/F6BHbJZ1ToyS2WxXTa2cXGNXnTc.png", "https://assets.ducktracks.fun/xlive/gameplay/feishu/charx/ZT0rbPecBodW4WxdabBcS0upn8b.png"),
    ReferenceItem("xgp-35207bd52aff45aa", "charx", "晓山瑞希", "https://assets.ducktracks.fun/xlive/gameplay/feishu/charx/WFSqbdZufompJJxDrn3cCKsSnrc.png", "https://assets.ducktracks.fun/xlive/gameplay/feishu/charx/YBFHbC0qKo0dVcxG9UBc5SGHnFh.png"),
    ReferenceItem("xgp-2de6187d192c4f07", "charx", "水王子", "https://assets.ducktracks.fun/xlive/gameplay/feishu/charx/HLSmbLKAao2h96xGC5ccoEUFnLd.png", "https://assets.ducktracks.fun/xlive/gameplay/feishu/charx/XjnjbbHeeoqjyVxRnL0cBg3QnYg.png"),
    ReferenceItem("xgp-7999ae524f954d96", "charx", "钢铁侠", "https://assets.ducktracks.fun/xlive/gameplay/feishu/charx/AlXkbaT3Tog5OJxirjIcGgyTnnc.png", "https://assets.ducktracks.fun/xlive/gameplay/feishu/charx/MZkFbk9g8ow6RAxki3WcSYS7nfd.png"),
    ReferenceItem("xgp-ab670f2a4f81467b", "charx", "有马加奈", "https://assets.ducktracks.fun/xlive/gameplay/feishu/charx/FH8FbALntoQkrgxW61mcT1kjnBh.png", "https://assets.ducktracks.fun/xlive/gameplay/feishu/charx/X0A2bMnxNoT8ZGxUQTJcDQTAnEe.png"),
    ReferenceItem("xgp-7cb0023d9d204f2a", "charx", "宵崎奏", "https://assets.ducktracks.fun/xlive/gameplay/feishu/charx/XHWJb94Coo5iYTxcDnEcChXHnlc.png", "https://assets.ducktracks.fun/xlive/gameplay/feishu/charx/SySIbcs1aoNRYjxHXRbc7pg0nLh.png"),
    ReferenceItem("xgp-541a9f88486a437c", "clothx", "女装", "https://assets.ducktracks.fun/xlive/gameplay/feishu/clothx/PerfbUxRdoGfADx70AJcQdEunse.png", "https://assets.ducktracks.fun/xlive/gameplay/feishu/clothx/VxRobHUleoX4hXxU2Jmc8X61nGh.png"),
    ReferenceItem("xgp-c202ac728d394737", "clothx", "女装", "https://assets.ducktracks.fun/xlive/gameplay/feishu/clothx/YPERbHwinom0VBxgLfrcfqHVnvh.png", "https://assets.ducktracks.fun/xlive/gameplay/feishu/clothx/ZW0Db6FVnoDYxvxKW8Hci8GfnGh.png"),
    ReferenceItem("xgp-8219a934f4cb4127", "clothx", "女装", "https://assets.ducktracks.fun/xlive/gameplay/feishu/clothx/Dq2xb499ioxVuOxCggKc9SVenmg.png", "https://assets.ducktracks.fun/xlive/gameplay/feishu/clothx/Fdp2bqvA1op6jcxOhOccW6TanAg.png"),
    ReferenceItem("xgp-3c48cf964ecb4708", "clothx", "女装", "https://assets.ducktracks.fun/xlive/gameplay/feishu/clothx/BrPlbbwlCoLkfTxYocHcTggTnze.png", "https://assets.ducktracks.fun/xlive/gameplay/feishu/clothx/CtjHbFmkEonao4xdR9mczjyPnYK.png"),
    ReferenceItem("xgp-503c92946ccf4473", "clothx", "女装", "https://assets.ducktracks.fun/xlive/gameplay/feishu/clothx/KXvEbvMZFoLAagx8pWlchQPVned.png", "https://assets.ducktracks.fun/xlive/gameplay/feishu/clothx/Nz59bPsfxoqgCGxf9Fkcja6bnd3.png"),
    ReferenceItem("xgp-229c12a3a4b24b88", "clothx", "男装", "https://assets.ducktracks.fun/xlive/gameplay/feishu/clothx/ZVG6biBqWoVG4DxZB5Ucmbeenng.png", "https://assets.ducktracks.fun/xlive/gameplay/feishu/clothx/RHJzbPb6toZmTrxqSbIcJiClnFg.png"),
    ReferenceItem("xgp-27715c4b365f40bb", "clothx", "男装", "https://assets.ducktracks.fun/xlive/gameplay/feishu/clothx/EiUHbamIqo0reVx8PmDc9stHnJf.png", "https://assets.ducktracks.fun/xlive/gameplay/feishu/clothx/AGE9bS7nqo2CXLxO1LoczDNknHg.png"),
    ReferenceItem("xgp-b0e63de2335f402d", "clothx", "男装", "https://assets.ducktracks.fun/xlive/gameplay/feishu/clothx/Ubynb85ZHofVGRxashWcaFuKnJg.png", "https://assets.ducktracks.fun/xlive/gameplay/feishu/clothx/J6pBbg86lorWeuxBHjtcTlnxnJk.png"),
    ReferenceItem("xgp-07e01d524eb64172", "clothx", "男装", "https://assets.ducktracks.fun/xlive/gameplay/feishu/clothx/Tutjb41ypofkOaxh4Oqc93WFnXf.png", "https://assets.ducktracks.fun/xlive/gameplay/feishu/clothx/OkJEbAoYTo3we9xtZ5bcpHnnnFb.png"),
    ReferenceItem("xgp-d70ed2cf67514476", "clothx", "男装", "https://assets.ducktracks.fun/xlive/gameplay/feishu/clothx/Td3jbfMProOPP4xVGoAc5d68nvf.png", "https://assets.ducktracks.fun/xlive/gameplay/feishu/clothx/L2Xab6u3eoH6mZxE82PcLYfhnEe.png"),
    ReferenceItem("xgp-d734939195fb45b4", "clothx", "男装", "https://assets.ducktracks.fun/xlive/gameplay/feishu/clothx/I4nnbxu7xoTdu2xnpjFc24zUnSg.png", "https://assets.ducktracks.fun/xlive/gameplay/feishu/clothx/Och4bTrQToFEnpx6TMHcWvq2nZd.png"),
    ReferenceItem("xgp-51c525357e8d4b2e", "clothx", "汉服", "https://assets.ducktracks.fun/xlive/gameplay/feishu/clothx/IitrbBKVaoPxNNxJ5Kkc8sN7n0T.png", "https://assets.ducktracks.fun/xlive/gameplay/feishu/clothx/QbUVbGFL4oubGSxcYETcyXfZnod.png"),
    ReferenceItem("xgp-61e24b1672dc4d5e", "dimx", "光头强", "https://assets.ducktracks.fun/xlive/gameplay/feishu/dimx/LHzEbR8DTowxLUxsDKBcX2ginag.png", "https://assets.ducktracks.fun/xlive/gameplay/feishu/dimx/EdUubKIAfoBw3pxKMSBcWbI5nkb.jpg"),
    ReferenceItem("xgp-7ca6c15cdd844ae3", "dimx", "熊二", "https://assets.ducktracks.fun/xlive/gameplay/feishu/dimx/GmVqbAQZroGe5jxemYkcIgv3nFf.png", "https://assets.ducktracks.fun/xlive/gameplay/feishu/dimx/Np1qbz5l1oetgVxUkxpcqapOnJh.jpg"),
    ReferenceItem("xgp-7a85e596eed34fb8", "dimx", "流萤", "https://assets.ducktracks.fun/xlive/gameplay/feishu/dimx/K7sLbUE8goU6WPxKAdqcAMEtnCb.png", "https://assets.ducktracks.fun/xlive/gameplay/feishu/dimx/Rca8brql3o8pCfxWnl7cF3c7nFg.png"),
    ReferenceItem("xgp-ef9b5ca7f4144954", "dimx", "祁煜", "https://assets.ducktracks.fun/xlive/gameplay/feishu/dimx/No2ibdX85oTLaixSaigcTdqVneb.png", "https://assets.ducktracks.fun/xlive/gameplay/feishu/dimx/H30nb42tuoeoMBxAmqqc6apDnnc.jpg"),
    ReferenceItem("xgp-65d1df3e09064a1d", "dimx", "沈星回", "https://assets.ducktracks.fun/xlive/gameplay/feishu/dimx/QNPsb8AfcoKGS7xg41gcx1asnAf.png", "https://assets.ducktracks.fun/xlive/gameplay/feishu/dimx/MX4AbvyY3oyhtDxCPJPc9gJRn4f.jpg"),
    ReferenceItem("xgp-a5b09fb47b644cef", "dimx", "雷电将军", "https://assets.ducktracks.fun/xlive/gameplay/feishu/dimx/BzZbbxtwToEIaVxKkGQcfarNnrd.png", "https://assets.ducktracks.fun/xlive/gameplay/feishu/dimx/GdfcbcuTZoRu25xzCIrcV2WAnXg.png"),
    ReferenceItem("xgp-98e9a87347824c62", "dimx", "马里奥", "https://assets.ducktracks.fun/xlive/gameplay/feishu/dimx/PfeEbsa6LoX4X7x8bCgcB7SYnpg.png", "https://assets.ducktracks.fun/xlive/gameplay/feishu/dimx/Z9Y3bvsTHomzOGxVbRBclyyvnmg.png"),
    ReferenceItem("xgp-8282bb9b591e4e4a", "dimx", "表情包", "https://assets.ducktracks.fun/xlive/gameplay/feishu/dimx/DyOmbc93DoUyrcxTOCMcAf19npd.png", "https://assets.ducktracks.fun/xlive/gameplay/feishu/dimx/Af82b1SwWoehL3x0DgvcBL2LnRc.png"),
    ReferenceItem("xgp-b7fba6b0158f452e", "dimx", "兔", "https://assets.ducktracks.fun/xlive/gameplay/feishu/dimx/JOK0bpJchoLHgHx7KyScluBnnZf.png", "https://assets.ducktracks.fun/xlive/gameplay/feishu/dimx/QbjLbagq2ofQTQxgqGzclszInzh.png"),
    ReferenceItem("xgp-f9aebe7537a14122", "dimx", "猪", "https://assets.ducktracks.fun/xlive/gameplay/feishu/dimx/S88VbMMdHocqDCxgPTEcjVz5n4d.png", "https://assets.ducktracks.fun/xlive/gameplay/feishu/dimx/CnfQbjblbo447HxdaOXcYwwtn3b.png"),
    ReferenceItem("xgp-8c894270c20b4a63", "dimx", "吉娃娃", "https://assets.ducktracks.fun/xlive/gameplay/feishu/dimx/InNUbIoi0oLwoSxLcrqcI6Inn1f.png", "https://assets.ducktracks.fun/xlive/gameplay/feishu/dimx/G2H6bPTzboYK9Hx0jfNcmDBnnbd.png"),
    ReferenceItem("xgp-eb387fc47f114760", "dimx", "小鸟", "https://assets.ducktracks.fun/xlive/gameplay/feishu/dimx/Vn3MbgYV5oi1bNxAsmucfbTVnBf.png", "https://assets.ducktracks.fun/xlive/gameplay/feishu/dimx/TkuobvHJyovixBxKQhLcJj0inoh.png"),
    ReferenceItem("xgp-218ec86016674037", "vibex", "王者荣耀孙权", "https://assets.ducktracks.fun/xlive/gameplay/feishu/vibex/Yz3ybbA6iokGbDxpQBacCnI6n1m.png", "https://assets.ducktracks.fun/xlive/gameplay/feishu/vibex/JUZAbWyMQo0057x6FQ9cbVBGnjf.jpg"),
    ReferenceItem("xgp-84c0a6202c17420c", "vibex", "哪吒老大", "https://assets.ducktracks.fun/xlive/gameplay/feishu/vibex/DI6ebgUkCofFdRxMh8McoJmfnkf.png", "https://assets.ducktracks.fun/xlive/gameplay/feishu/vibex/M0DhbS86moarEIxMXkTce9TznW0.png"),
    ReferenceItem("xgp-4c138317a3524f85", "vibex", "芭比特", "https://assets.ducktracks.fun/xlive/gameplay/feishu/vibex/ZdhNbWMuloryVmxCt78caKD6n86.png", "https://assets.ducktracks.fun/xlive/gameplay/feishu/vibex/GXJPbd2edoRQWpx5GLrcat6snbc.png"),
    ReferenceItem("xgp-79f704db0763408b", "vibex", "神代类", "https://assets.ducktracks.fun/xlive/gameplay/feishu/vibex/JnCCbS7yyoAd39xenRgcQUY3nUe.png", "https://assets.ducktracks.fun/xlive/gameplay/feishu/vibex/V9dnboWqWoGiZJxa9F8cjqZLnEf.jpg"),
    ReferenceItem("xgp-29dc37cf2e9c439b", "vibex", "艾莎", "https://assets.ducktracks.fun/xlive/gameplay/feishu/vibex/BoS1bnIaNoiWlDxPdbHczx1Yn5e.png", "https://assets.ducktracks.fun/xlive/gameplay/feishu/vibex/Rz2TbQ5DjojLFixb5z7ct7rKnAY.png"),
    ReferenceItem("xgp-c48ebc9c910e4001", "vibex", "军阀奶龙", "https://assets.ducktracks.fun/xlive/gameplay/feishu/vibex/N7yObh5SWoAte0xb9kZcIu1PnSe.png", "https://assets.ducktracks.fun/xlive/gameplay/feishu/vibex/WARNbPdBkozJxuxQau1czlL1nhd.png"),
    ReferenceItem("xgp-2d5faec1dc4a4d54", "vibex", "高泰明 香蕉先生", "https://assets.ducktracks.fun/xlive/gameplay/feishu/vibex/U5vQbdUW1oSiTRxuCWLcXzQ9nyc.png", "https://assets.ducktracks.fun/xlive/gameplay/feishu/vibex/V89Ebv8d9oQl4pxr34ccbuX5nsf.jpg"),
    ReferenceItem("xgp-191d9e8bb0f646b3", "vibex", "香蕉先生", "https://assets.ducktracks.fun/xlive/gameplay/feishu/vibex/LDTQbzHM3ocEzTxOfoEc43URnEb.png", "https://assets.ducktracks.fun/xlive/gameplay/feishu/vibex/MlYdbyWFmowREax0Nd1c6NxSn7D.png"),
    ReferenceItem("xgp-4a7d13374af34521", "vibex", "芭比", "https://assets.ducktracks.fun/xlive/gameplay/feishu/vibex/CLNxb21qWoAEfoxebx9ctKMyn3b.png", "https://assets.ducktracks.fun/xlive/gameplay/feishu/vibex/TGDTbwadOodTTMxpJ6UcAwMRnOe.png"),
    ReferenceItem("xgp-29b030ea65a94b5b", "vibex", "星野爱", "https://assets.ducktracks.fun/xlive/gameplay/feishu/vibex/DGUrbC0U9oHJr4xZ0Q8c9GlUnIS.png", "https://assets.ducktracks.fun/xlive/gameplay/feishu/vibex/MmP9bfi4wo01YsxyyDSc4za4nVg.png"),
    ReferenceItem("xgp-d9d7c23c31cd46a3", "vibex", "雷电将军", "https://assets.ducktracks.fun/xlive/gameplay/feishu/vibex/KqEFbwLdboud2IxMqCWcZ0ndnWf.png", "https://assets.ducktracks.fun/xlive/gameplay/feishu/vibex/HsTYbm73YoM2y5xapaDc61HOnhb.png"),
    ReferenceItem("xgp-701f035faabb44af", "vibex", "有马加奈", "https://assets.ducktracks.fun/xlive/gameplay/feishu/vibex/Fm9ibOPdWosDXGxsUsQcQLU9nob.png", "https://assets.ducktracks.fun/xlive/gameplay/feishu/vibex/EaBzbS7eUofJmXxWcpIcHf0nnAh.png"),
    ReferenceItem("xgp-ed36c122bbcc4402", "vibex", "朵拉", "https://assets.ducktracks.fun/xlive/gameplay/feishu/vibex/V2ysbi4IUokiLAxdwVWcjRnxnEb.png", "https://assets.ducktracks.fun/xlive/gameplay/feishu/vibex/XLoDb1yygo2y5ExO638ccBNzneg.png"),
    ReferenceItem("xgp-f1e081486e484a0d", "vibex", "约尔", "https://assets.ducktracks.fun/xlive/gameplay/feishu/vibex/LaSpbL9QXo21Hyx0qTBcpBCYnff.png", "https://assets.ducktracks.fun/xlive/gameplay/feishu/vibex/AqJWbLxQ0odd2ex6HYdcrfeMn7j.png"),
    ReferenceItem("xgp-ecf1d08f17254201", "vibex", "蛇喰梦子", "https://assets.ducktracks.fun/xlive/gameplay/feishu/vibex/R1iGbn6ngoEIvfxWgx4c2pN5nIc.png", "https://assets.ducktracks.fun/xlive/gameplay/feishu/vibex/RUEubRSkQoyjeRxzBDHcLk3KnKg.png"),
)
