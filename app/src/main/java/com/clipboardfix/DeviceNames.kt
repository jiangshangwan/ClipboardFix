package com.clipboardfix

import android.os.Build
import java.util.Locale

/**
 * 设备代号 → 真实机型名（如 haotian → 小米15 Pro）。
 *
 * Build.DEVICE / Build.PRODUCT 是设备代号（nuwa、houji 之类），直接展示不友好；
 * 这里查表翻译为营销名，查不到回退为 品牌 + Build.MODEL。
 */
object DeviceNames {

    private val NAMES = mapOf(
        // 小米数字系列
        "umi" to "小米10 / 小米10 Pro",
        "cas" to "小米10 至尊纪念版",
        "thyme" to "小米10S",
        "monet" to "小米10 青春版",
        "venus" to "小米11",
        "mars" to "小米11 Pro",
        "star" to "小米11 Ultra",
        "lisa" to "小米11 青春版",
        "cupid" to "小米12",
        "zeus" to "小米12 Pro",
        "psyche" to "小米12X",
        "mayfly" to "小米12S",
        "unicorn" to "小米12S Pro",
        "thor" to "小米12S Ultra",
        "fuxi" to "小米13",
        "nuwa" to "小米13 Pro",
        "ishtar" to "小米13 Ultra",
        "houji" to "小米14",
        "shennong" to "小米14 Pro",
        "shennong_t" to "小米14 Pro 卫星版",
        "aurora" to "小米14 Ultra",
        "dada" to "小米15",
        "haotian" to "小米15 Pro",
        "dijun" to "小米15S Pro",
        "xuanyuan" to "小米15 Ultra",
        "pudding" to "小米17",
        "pandora" to "小米17 Pro",
        "popsicle" to "小米17 Pro Max",
        // 小米 T 系列 / 红米（同代号双命名）
        "plato" to "小米12T",
        "diting" to "小米12T Pro / Redmi K50 Ultra",
        "aristotle" to "小米13T",
        "corot" to "小米13T Pro / Redmi K60 至尊版",
        "degas" to "小米14T",
        "rothko" to "Redmi K70 至尊版",
        "vermeer" to "Redmi K70",
        "manet" to "Redmi K70 Pro",
        "duchamp" to "Redmi K70E",
        "mondrian" to "Redmi K60",
        "socrates" to "Redmi K60 Pro",
        "rembrandt" to "Redmi K60E",
        "zorn" to "Redmi K80",
        "miro" to "Redmi K80 Pro",
        // Civi 系列
        "mona" to "小米Civi",
        "zijin" to "小米Civi 1S",
        "ziyi" to "小米Civi 2",
        "yuechu" to "小米Civi 3",
        "chenfeng" to "小米Civi 4 Pro",
        "luming" to "小米Civi 5 Pro",
        // MIX 系列
        "chiron" to "小米MIX 2",
        "polaris" to "小米MIX 2S",
        "cetus" to "小米MIX Fold",
        "zizhan" to "小米MIX Fold 2",
        "babylon" to "小米MIX Fold 3",
        "goku" to "小米MIX Fold 4",
        "ruyi" to "小米MIX Flip",
        "bixi" to "小米MIX Flip 2",
        // 平板系列
        "nabu" to "小米平板5",
        "elish" to "小米平板5 Pro",
        "enuma" to "小米平板5 Pro 5G",
        "pipa" to "小米平板6",
        "liuqin" to "小米平板6 Pro",
        "yudi" to "小米平板6 Max 14",
        "sheng" to "小米平板6S Pro 12.4",
        "uke" to "小米平板7",
        "muyu" to "小米平板7 Pro",
        "violin" to "小米平板7S Pro 12.5",
        "jinghu" to "小米平板7 Ultra",
        "yupei" to "小米平板8",
        // 更早机型（MIUI 时代遗存）
        "sagit" to "小米6",
        "dipper" to "小米8",
        "cepheus" to "小米9"
    )

    /** 真实机型名；查不到回退 品牌 + Build.MODEL。 */
    @JvmStatic
    fun marketName(): String {
        var name = lookup(Build.DEVICE)
        if (name == null) name = lookup(Build.PRODUCT)
        if (name == null) name = lookup(stripRegion(Build.DEVICE))
        if (name == null) name = lookup(stripRegion(Build.PRODUCT))
        return name ?: (Build.BRAND + " " + Build.MODEL)
    }

    private fun lookup(key: String?): String? {
        if (key.isNullOrEmpty()) return null
        return NAMES[key.lowercase(Locale.ROOT)]
    }

    /** 去掉机型代号上的区域/版本后缀（如 nuwa_global → nuwa）。 */
    private fun stripRegion(device: String?): String? {
        if (device == null) return null
        val i = device.indexOf('_')
        return if (i > 0) device.substring(0, i) else device
    }
}
