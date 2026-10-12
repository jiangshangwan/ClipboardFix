package com.clipboardfix;

import android.os.Build;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 设备代号 → 真实机型名（如 haotian → 小米15 Pro）。
 * <p>
 * Build.DEVICE / Build.PRODUCT 是设备代号（nuwa、houji 之类），直接展示不友好；
 * 这里查表翻译为营销名，查不到回退为 品牌 + Build.MODEL。
 */
public final class DeviceNames {

    private static final Map<String, String> NAMES = new HashMap<>();

    static {
        // 小米数字系列
        NAMES.put("umi", "小米10 / 小米10 Pro");
        NAMES.put("cas", "小米10 至尊纪念版");
        NAMES.put("thyme", "小米10S");
        NAMES.put("monet", "小米10 青春版");
        NAMES.put("venus", "小米11");
        NAMES.put("mars", "小米11 Pro");
        NAMES.put("star", "小米11 Ultra");
        NAMES.put("lisa", "小米11 青春版");
        NAMES.put("cupid", "小米12");
        NAMES.put("zeus", "小米12 Pro");
        NAMES.put("psyche", "小米12X");
        NAMES.put("mayfly", "小米12S");
        NAMES.put("unicorn", "小米12S Pro");
        NAMES.put("thor", "小米12S Ultra");
        NAMES.put("fuxi", "小米13");
        NAMES.put("nuwa", "小米13 Pro");
        NAMES.put("ishtar", "小米13 Ultra");
        NAMES.put("houji", "小米14");
        NAMES.put("shennong", "小米14 Pro");
        NAMES.put("shennong_t", "小米14 Pro 卫星版");
        NAMES.put("aurora", "小米14 Ultra");
        NAMES.put("dada", "小米15");
        NAMES.put("haotian", "小米15 Pro");
        NAMES.put("dijun", "小米15S Pro");
        NAMES.put("xuanyuan", "小米15 Ultra");
        NAMES.put("pudding", "小米17");
        NAMES.put("pandora", "小米17 Pro");
        NAMES.put("popsicle", "小米17 Pro Max");
        // 小米 T 系列 / 红米（同代号双命名）
        NAMES.put("plato", "小米12T");
        NAMES.put("diting", "小米12T Pro / Redmi K50 Ultra");
        NAMES.put("aristotle", "小米13T");
        NAMES.put("corot", "小米13T Pro / Redmi K60 至尊版");
        NAMES.put("degas", "小米14T");
        NAMES.put("rothko", "Redmi K70 至尊版");
        NAMES.put("vermeer", "Redmi K70");
        NAMES.put("manet", "Redmi K70 Pro");
        NAMES.put("duchamp", "Redmi K70E");
        NAMES.put("mondrian", "Redmi K60");
        NAMES.put("socrates", "Redmi K60 Pro");
        NAMES.put("rembrandt", "Redmi K60E");
        NAMES.put("zorn", "Redmi K80");
        NAMES.put("miro", "Redmi K80 Pro");
        // Civi 系列
        NAMES.put("mona", "小米Civi");
        NAMES.put("zijin", "小米Civi 1S");
        NAMES.put("ziyi", "小米Civi 2");
        NAMES.put("yuechu", "小米Civi 3");
        NAMES.put("chenfeng", "小米Civi 4 Pro");
        NAMES.put("luming", "小米Civi 5 Pro");
        // MIX 系列
        NAMES.put("chiron", "小米MIX 2");
        NAMES.put("polaris", "小米MIX 2S");
        NAMES.put("cetus", "小米MIX Fold");
        NAMES.put("zizhan", "小米MIX Fold 2");
        NAMES.put("babylon", "小米MIX Fold 3");
        NAMES.put("goku", "小米MIX Fold 4");
        NAMES.put("ruyi", "小米MIX Flip");
        NAMES.put("bixi", "小米MIX Flip 2");
        // 平板系列
        NAMES.put("nabu", "小米平板5");
        NAMES.put("elish", "小米平板5 Pro");
        NAMES.put("enuma", "小米平板5 Pro 5G");
        NAMES.put("pipa", "小米平板6");
        NAMES.put("liuqin", "小米平板6 Pro");
        NAMES.put("yudi", "小米平板6 Max 14");
        NAMES.put("sheng", "小米平板6S Pro 12.4");
        NAMES.put("uke", "小米平板7");
        NAMES.put("muyu", "小米平板7 Pro");
        NAMES.put("violin", "小米平板7S Pro 12.5");
        NAMES.put("jinghu", "小米平板7 Ultra");
        NAMES.put("yupei", "小米平板8");
        // 更早机型（MIUI 时代遗存）
        NAMES.put("sagit", "小米6");
        NAMES.put("dipper", "小米8");
        NAMES.put("cepheus", "小米9");
    }

    private DeviceNames() {
    }

    /** 真实机型名；查不到回退 品牌 + Build.MODEL。 */
    public static String marketName() {
        String name = lookup(Build.DEVICE);
        if (name == null) {
            name = lookup(Build.PRODUCT);
        }
        if (name == null) {
            name = lookup(stripRegion(Build.DEVICE));
        }
        if (name == null) {
            name = lookup(stripRegion(Build.PRODUCT));
        }
        return name != null ? name : Build.BRAND + " " + Build.MODEL;
    }

    private static String lookup(String key) {
        if (key == null || key.isEmpty()) {
            return null;
        }
        return NAMES.get(key.toLowerCase(Locale.ROOT));
    }

    /** 去掉机型代号上的区域/版本后缀（如 nuwa_global → nuwa）。 */
    private static String stripRegion(String device) {
        if (device == null) {
            return null;
        }
        int i = device.indexOf('_');
        return i > 0 ? device.substring(0, i) : device;
    }
}
