/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

/**
 * 3D 渲染显示风格模式
 */
enum class RenderMode3D(val id: String) {
    CLAY("clay"),           // 石膏素模 (Matcap / 漫反射体积)
    SHADING("shading"),     // 可调单向光源明暗切面
    WIREFRAME("wireframe"), // 结构透视线框
    ;

    companion object {
        fun fromId(id: String): RenderMode3D =
            entries.find { it.id.equals(id, ignoreCase = true) } ?: CLAY
    }
}

/**
 * 内置基础 3D 参考几何与人体素模
 */
enum class BuiltinModel3D(val id: String, val displayNameRes: String) {
    CUBE("cube", "透视方块"),
    CYLINDER("cylinder", "圆柱体"),
    SPHERE("sphere", "球体"),
    HEAD_MANNEQUIN("head", "头部比例素模"),
    ;

    companion object {
        fun fromId(id: String): BuiltinModel3D? =
            entries.find { it.id.equals(id, ignoreCase = true) }
    }
}

/**
 * 3D 交互手势模式 (单指触摸视口时的操控目标)
 */
enum class Interaction3DMode {
    ROTATE, // 旋转模型视角
    PAN,    // 平移模型位置
    LIGHT,  // 调整光照朝向
}

/**
 * 3D 图层状态与渲染参数
 */
data class Model3DState(
    val layerId: Long = 0L,
    val modelType: String = BuiltinModel3D.CUBE.id, // "cube", "sphere", "file:/path/to/mesh.obj"
    val rotX: Float = 15f,     // 俯仰角 (Pitch)
    val rotY: Float = -30f,    // 偏航角 (Yaw)
    val rotZ: Float = 0f,      // 翻滚角 (Roll)
    val panX: Float = 0f,      // 视口水平偏移 [-1f..1f]
    val panY: Float = 0f,      // 视口垂直偏移 [-1f..1f]
    val zoom: Float = 1.0f,    // 视口距离缩放 [0.2f..5.0f]
    val lightYaw: Float = 45f, // 定向光源水平角
    val lightPitch: Float = 35f,// 定向光源俯仰角
    val renderMode: RenderMode3D = RenderMode3D.CLAY,
    val baseColor: Long = 0xFFD8D4CE, // 默认灰模/素模底色 (莫兰迪暖灰)
    val wireframeColor: Long = 0xFF36383B, // 默认线框颜色 (深灰炭笔色)
) {
    fun isBuiltin(): Boolean = BuiltinModel3D.fromId(modelType) != null

    /**
     * 编码为用于工程 JSON 存储的紧凑键值对字符串
     */
    fun toJsonString(): String {
        return """{"layerId":$layerId,"modelType":"${modelType.replace("\"", "\\\"")}","rotX":$rotX,"rotY":$rotY,"rotZ":$rotZ,"panX":$panX,"panY":$panY,"zoom":$zoom,"lightYaw":$lightYaw,"lightPitch":$lightPitch,"renderMode":"${renderMode.id}","baseColor":$baseColor,"wireframeColor":$wireframeColor}"""
    }

    companion object {
        fun fromJson(json: String): Model3DState? {
            return try {
                val clean = json.trim().removeSurrounding("{", "}")
                val pairs = clean.split(",")
                var layerId = 0L
                var modelType = BuiltinModel3D.CUBE.id
                var rotX = 15f
                var rotY = -30f
                var rotZ = 0f
                var panX = 0f
                var panY = 0f
                var zoom = 1.0f
                var lightYaw = 45f
                var lightPitch = 35f
                var renderMode = RenderMode3D.CLAY
                var baseColor = 0xFFD8D4CE
                var wireframeColor = 0xFF36383B

                for (p in pairs) {
                    val kv = p.split(":", limit = 2)
                    if (kv.size != 2) continue
                    val key = kv[0].trim().trim('"', '\'')
                    val value = kv[1].trim().trim('"', '\'')
                    when (key) {
                        "layerId" -> layerId = value.toLongOrNull() ?: 0L
                        "modelType" -> modelType = value
                        "rotX" -> rotX = value.toFloatOrNull() ?: 15f
                        "rotY" -> rotY = value.toFloatOrNull() ?: -30f
                        "rotZ" -> rotZ = value.toFloatOrNull() ?: 0f
                        "panX" -> panX = value.toFloatOrNull() ?: 0f
                        "panY" -> panY = value.toFloatOrNull() ?: 0f
                        "zoom" -> zoom = value.toFloatOrNull() ?: 1.0f
                        "lightYaw" -> lightYaw = value.toFloatOrNull() ?: 45f
                        "lightPitch" -> lightPitch = value.toFloatOrNull() ?: 35f
                        "renderMode" -> renderMode = RenderMode3D.fromId(value)
                        "baseColor" -> baseColor = value.toLongOrNull() ?: 0xFFD8D4CE
                        "wireframeColor" -> wireframeColor = value.toLongOrNull() ?: 0xFF36383B
                    }
                }
                Model3DState(
                    layerId = layerId,
                    modelType = modelType,
                    rotX = rotX,
                    rotY = rotY,
                    rotZ = rotZ,
                    panX = panX,
                    panY = panY,
                    zoom = zoom,
                    lightYaw = lightYaw,
                    lightPitch = lightPitch,
                    renderMode = renderMode,
                    baseColor = baseColor,
                    wireframeColor = wireframeColor,
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}
