package com.bianhequ.bhqmap.data

import org.osmdroid.util.GeoPoint

/**
 * 闲置厂房（v0.7.28）。
 *
 * 来源：`边合区企业台账.xlsx` 的**「闲置厂房」工作表**（洲哥 2026-09-24 新建），
 * 经 `.build_idle_factories.py` 生成 `assets/idle_factories.json` 打进包里。
 * 台账里是「序号 / 闲置厂房名称 / 闲置面积 / 经度 / 纬度」五列，一条 = 一处闲置厂房。
 *
 * ------------------------------------------------------------------
 * 为什么不做成企业（Enterprise）的一个字段
 * ------------------------------------------------------------------
 * 闲置厂房**不是一类企业**，它是**一段时期的资产状态**：
 *   · 一家企业可能同时登记两处闲置厂房（台账里「曲美木业」就有 3 条，坐标各不同）；
 *   · 一处闲置厂房在租出去之后就该从这层消失，而企业档案不能跟着消失；
 *   · 这层的开法与三个样式开关**互斥**（见 [com.bianhequ.bhqmap.map.IdleFactoryOverlay]），
 *     它是个独立图层，不是标签样式的第 N 种。
 * 所以是独立的数据类 + 独立的图层，只在「坐标同用 WGS-84」这一点上与企业共享。
 *
 * ------------------------------------------------------------------
 * 面积：原文照存 + 另抽一个数
 * ------------------------------------------------------------------
 * [areaText] 是台账原文（`闲置6500平方米`），**一个字都不改** —— 那是洲哥填的事实，
 * 地图上要显示的就是它。但它没法比较大小（"2.9万平方米" vs "2682.45平方米"），
 * 而标签挤不下时要决定"先保谁"，靠的就是面积。
 * 于是另存 [areaM2]：从原文里抽出的**第一个**面积数字折算成平方米。
 * 台账里第一个数恒是"占地/闲置"那个（`占地1.44万平方米，建筑面积7000平方米`
 * → 取 1.44 万 = 14400），建筑面积排在后面、不参与。
 * [areaM2] **只用于排序**，App 不显示它（要显示的是 [areaText] 原文）。
 */
class IdleFactory(
    /** 稳定键：`if-` + 台账行号（与企业种子的 `td-` 同一套命名法）。只作键用，不展示 */
    val id: String,

    /** 闲置厂房名称，台账原文（如 `B-7-2地块 勃源冷链物流加工项目`） */
    val name: String,

    /** 闲置面积，台账原文（如 `闲置6500平方米`），**不改写** */
    val areaText: String,

    /** 从 [areaText] 抽出的可比面积（平方米），**仅用于排序**；抽不出时为 0 */
    val areaM2: Double,

    /** 经度（WGS-84，与全工程坐标系一致，零转换） */
    val lon: Double,

    /** 纬度（WGS-84） */
    val lat: Double
) {

    /** 坐标是否可用。台账这一表 25 条坐标齐全，判空是防将来补录时留空 */
    val hasCoord: Boolean
        get() = !lon.isNaN() && !lat.isNaN() && (lon != 0.0 || lat != 0.0)

    /**
     * 投影用点。**惰性算一次就存住** —— [com.bianhequ.bhqmap.map.IdleFactoryOverlay]
     * 每帧都要投影这些点，逐帧 `GeoPoint(lat, lon)` 会白白产生一堆临时对象。
     */
    val point: GeoPoint by lazy { GeoPoint(lat, lon) }

    /**
     * 标签第一行：名称。含空格时按第一个空格折成两行 ——
     * 台账里有 3 条是「地块编号 + 项目名」的写法（`B-7-2地块 勃源冷链物流加工项目`，
     * 单行 18 字，12sp 下约 470 px，一条就吃掉半屏宽），
     * 在空格处断开后上行 5~6 字、下行 12~13 字，既窄又好读。
     * 没有空格的名称（其余 22 条）就是一行。
     *
     * 构造成员而非 getter：图层每帧要读它两次（量宽、落笔），
     * 写成 `get()` 就是每帧 50 次字符串切分 + 列表分配。
     */
    val nameLines: List<String> = run {
        val i = name.indexOf(' ')
        if (i <= 0) {
            listOf(name)
        } else {
            val a = name.substring(0, i).trim()
            val b = name.substring(i + 1).trim()
            if (a.isEmpty() || b.isEmpty()) listOf(name) else listOf(a, b)
        }
    }

    /**
     * 标签第二行起：面积。同样不硬折 ——
     * 只在**原文自带逗号**处断开（台账里仅 1 条：`占地1.44万平方米，建筑面积7000平方米`，
     * 22 字单行约 600 px）。断在两件事之间（占地 / 建筑面积）语义正好，不算切词。
     */
    val areaLines: List<String> = run {
        if (!areaText.contains('，')) {
            listOf(areaText)
        } else {
            val parts = areaText.split('，').map { it.trim() }.filter { it.isNotEmpty() }
            if (parts.isEmpty()) listOf(areaText) else parts
        }
    }
}
