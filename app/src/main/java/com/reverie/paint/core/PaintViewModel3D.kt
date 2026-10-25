/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import com.reverie.paint.R
import com.reverie.paint.model.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.ConcurrentHashMap

private val meshCache = ConcurrentHashMap<String, Mesh3D>()
private val skinnedModelCache = ConcurrentHashMap<String, SkinnedMesh3D>()

/**
 * 加载或获取已解析的带骨骼蒙皮 3D 模型 (支持内置 GLB 与外部导入的 .glb / .vrm / .pmx)
 */
internal fun PaintViewModel.getSkinnedModelForState(state: Model3DState): SkinnedMesh3D? {
    val key = state.modelType
    skinnedModelCache[key]?.let { return it }

    val skinned: SkinnedMesh3D? = when (key.lowercase()) {
        "boy", BuiltinModel3D.ANIME_BOY.id, BuiltinModel3D.BODY_MANNEQUIN.id, BuiltinModel3D.BODY_SIT.id -> {
            try {
                appContext.assets.open("3d_models/anime_boy.glb").use { GlbParser.parse(it) }
            } catch (e: Exception) {
                Log.e("RP_3D", "Failed to load anime_boy.glb from assets", e)
                null
            }
        }
        "girl", BuiltinModel3D.ANIME_GIRL.id -> {
            try {
                appContext.assets.open("3d_models/anime_girl.glb").use { GlbParser.parse(it) }
            } catch (e: Exception) {
                Log.e("RP_3D", "Failed to load anime_girl.glb from assets", e)
                null
            }
        }
        "cat", BuiltinModel3D.CAT.id -> {
            try {
                appContext.assets.open("3d_models/anime_cat.glb").use { GlbParser.parse(it) }
            } catch (e: Exception) {
                Log.e("RP_3D", "Failed to load anime_cat.glb from assets", e)
                null
            }
        }
        else -> {
            if (key.startsWith("file:") || key.startsWith("uri:")) {
                try {
                    val stream = if (key.startsWith("file:")) {
                        val file = java.io.File(key.substring(5))
                        if (file.exists()) file.inputStream() else null
                    } else {
                        val uri = Uri.parse(key.substring(4))
                        appContext.contentResolver.openInputStream(uri)
                    }
                    stream?.use { s ->
                        when {
                            key.endsWith(".glb", ignoreCase = true) || key.endsWith(".vrm", ignoreCase = true) -> GlbParser.parse(s)
                            key.endsWith(".pmx", ignoreCase = true) -> PmxParser.parse(s)
                            else -> null
                        }
                    }
                } catch (e: Exception) {
                    Log.e("RP_3D", "Failed to parse imported skinned model from: $key", e)
                    null
                }
            } else {
                null
            }
        }
    }

    if (skinned != null) {
        if (skinnedModelCache.size > 20) skinnedModelCache.clear()
        skinnedModelCache[key] = skinned
    }
    return skinned
}

/**
 * 将姿态预设与关节点微调角度组合为骨骼局部欧拉旋转映射表 [BoneName -> [Pitch, Yaw, Roll]]
 */
private fun buildEulerMap(
    posePreset: String,
    jointAngles: Map<String, Float>,
    isCat: Boolean
): Map<String, FloatArray> {
    val map = HashMap<String, FloatArray>()

    fun addJoint(boneNames: List<String>, pitch: Float, yaw: Float, roll: Float) {
        val angles = floatArrayOf(pitch, yaw, roll)
        for (name in boneNames) {
            map[name] = angles
        }
    }

    if (isCat) {
        var headP = 0f; var spineArch = 0f
        var flLeg = 0f; var frLeg = 0f; var hlLeg = 0f; var hrLeg = 0f
        var tailP = 35f; var tailY = 10f

        when (posePreset.lowercase()) {
            "sit" -> {
                spineArch = -15f; headP = 12f
                flLeg = -5f; frLeg = -5f; hlLeg = -70f; hrLeg = -70f
                tailP = -45f; tailY = 35f
            }
            "loaf" -> {
                spineArch = 8f; flLeg = -75f; frLeg = -75f; hlLeg = -80f; hrLeg = -80f
                tailP = -60f; tailY = 50f
            }
            "stretch" -> {
                spineArch = 28f; headP = -15f
                flLeg = 45f; frLeg = 45f; hlLeg = 20f; hrLeg = 20f
                tailP = 65f; tailY = 5f
            }
        }

        headP += (jointAngles["cat_head_pitch"] ?: 0f)
        val headY = (jointAngles["cat_head_yaw"] ?: 0f)
        spineArch += (jointAngles["cat_spine_arch"] ?: 0f)
        flLeg += (jointAngles["cat_fl_leg"] ?: 0f)
        frLeg += (jointAngles["cat_fr_leg"] ?: 0f)
        hlLeg += (jointAngles["cat_hl_leg"] ?: 0f)
        hrLeg += (jointAngles["cat_hr_leg"] ?: 0f)
        tailP += (jointAngles["cat_tail_pitch"] ?: 0f)
        tailY += (jointAngles["cat_tail_yaw"] ?: 0f)

        addJoint(listOf("Head", "head"), headP, headY, 0f)
        addJoint(listOf("Spine", "spine"), spineArch, 0f, 0f)
        addJoint(listOf("leftFrontLeg", "LeftFrontLeg", "l_front_leg"), flLeg, 0f, 0f)
        addJoint(listOf("rightFrontLeg", "RightFrontLeg", "r_front_leg"), frLeg, 0f, 0f)
        addJoint(listOf("leftHindLeg", "LeftHindLeg", "l_hind_thigh"), hlLeg, 0f, 0f)
        addJoint(listOf("rightHindLeg", "RightHindLeg", "r_hind_thigh"), hrLeg, 0f, 0f)
        addJoint(listOf("Tail1", "tail1"), tailP, tailY, 0f)
        addJoint(listOf("Tail2", "tail2"), 22f, 12f, 0f)
        addJoint(listOf("Tail3", "tail3"), 22f, 8f, 0f)
        addJoint(listOf("Tail4", "tail4"), 15f, 5f, 0f)
    } else {
        var spineP = 0f; var spineY = 0f
        var headP = 0f; var headY = 0f
        var lArmP = 0f; var lArmY = 0f; var lArmRoll = 0f
        var rArmP = 0f; var rArmY = 0f; var rArmRoll = 0f
        var lElbowP = 0f; var rElbowP = 0f
        var lThighP = 0f; var lThighY = 0f
        var rThighP = 0f; var rThighY = 0f
        var lKneeP = 0f; var rKneeP = 0f

        when (posePreset.lowercase()) {
            "stand" -> {
                // 原生标准自然站姿：A-pose 模型无需额外偏移
            }
            "sit" -> {
                // 坐姿: 大腿向前平抬 80°，小腿垂直下垂 80°，双手微屈搭放于腿前
                lThighP = 80f; lKneeP = -80f
                rThighP = 80f; rKneeP = -80f
                lArmP = 25f; lElbowP = 40f
                rArmP = 25f; rElbowP = 40f
                spineP = 5f
            }
            "action" -> {
                // 动态奔跑姿态
                spineP = 12f; spineY = -10f
                headP = -6f; headY = 10f
                lArmP = 45f; lElbowP = 50f
                rArmP = -40f; rElbowP = 30f
                lThighP = 55f; lKneeP = -55f
                rThighP = -35f; rKneeP = -30f
            }
            "crossed" -> {
                // 双手抱胸
                spineP = -4f
                lArmP = 35f; lArmRoll = 30f; lElbowP = 85f
                rArmP = 35f; rArmRoll = -30f; rElbowP = 85f
            }
            "hips" -> {
                // 双手叉腰
                lArmP = -15f; lArmRoll = -25f; lElbowP = 75f
                rArmP = -15f; rArmRoll = 25f; rElbowP = 75f
            }
            "kneel" -> {
                // 单膝下跪
                spineP = 12f
                lThighP = 75f; lKneeP = -75f
                rThighP = -15f; rKneeP = -85f
                rArmP = 35f; rElbowP = 40f
            }
        }

        spineP += (jointAngles["spine_pitch"] ?: 0f)
        spineY += (jointAngles["spine_yaw"] ?: 0f)
        headP += (jointAngles["head_pitch"] ?: 0f)
        headY += (jointAngles["head_yaw"] ?: 0f)

        lArmP += (jointAngles["l_arm_pitch"] ?: 0f)
        lArmY += (jointAngles["l_arm_yaw"] ?: 0f)
        lElbowP += (jointAngles["l_elbow"] ?: 0f)

        rArmP += (jointAngles["r_arm_pitch"] ?: 0f)
        rArmY += (jointAngles["r_arm_yaw"] ?: 0f)
        rElbowP += (jointAngles["r_elbow"] ?: 0f)

        lThighP += (jointAngles["l_thigh_pitch"] ?: 0f)
        lThighY += (jointAngles["l_thigh_yaw"] ?: 0f)
        lKneeP -= (jointAngles["l_knee"] ?: 0f)

        rThighP += (jointAngles["r_thigh_pitch"] ?: 0f)
        rThighY += (jointAngles["r_thigh_yaw"] ?: 0f)
        rKneeP -= (jointAngles["r_knee"] ?: 0f)

        addJoint(listOf("head", "Head"), headP, headY, 0f)
        addJoint(listOf("spine", "spine_01", "Spine"), spineP, spineY, 0f)
        addJoint(listOf("chest", "spine_02", "Chest", "UpperChest"), spineP * 0.5f, spineY * 0.5f, 0f)

        addJoint(listOf("leftUpperArm", "upperarm.l", "LeftArm", "LeftUpperArm"), lArmP, lArmY, lArmRoll)
        addJoint(listOf("leftLowerArm", "lowerarm.l", "LeftForeArm", "LeftLowerArm"), lElbowP, 0f, 0f)

        addJoint(listOf("rightUpperArm", "upperarm.r", "RightArm", "RightUpperArm"), rArmP, rArmY, rArmRoll)
        addJoint(listOf("rightLowerArm", "lowerarm.r", "RightForeArm", "RightLowerArm"), rElbowP, 0f, 0f)

        addJoint(listOf("leftUpperLeg", "thigh.l", "LeftUpLeg", "LeftUpperLeg", "LeftThigh"), lThighP, lThighY, 0f)
        addJoint(listOf("leftLowerLeg", "calf.l", "LeftLeg", "LeftLowerLeg", "LeftShin"), lKneeP, 0f, 0f)

        addJoint(listOf("rightUpperLeg", "thigh.r", "RightUpLeg", "RightUpperLeg", "RightThigh"), rThighP, rThighY, 0f)
        addJoint(listOf("rightLowerLeg", "calf.r", "RightLeg", "RightLowerLeg", "RightShin"), rKneeP, 0f, 0f)
    }

    // 通用骨骼直接旋转支持 (支持任意 GLB / VRM / PMX 骨骼名称)
    for ((key, value) in jointAngles) {
        val (boneName, axis) = when {
            key.endsWith("_p") -> Pair(key.removeSuffix("_p"), 0)
            key.endsWith("_pitch") -> Pair(key.removeSuffix("_pitch"), 0)
            key.endsWith("_y") -> Pair(key.removeSuffix("_y"), 1)
            key.endsWith("_yaw") -> Pair(key.removeSuffix("_yaw"), 1)
            key.endsWith("_r") -> Pair(key.removeSuffix("_r"), 2)
            key.endsWith("_roll") -> Pair(key.removeSuffix("_roll"), 2)
            else -> continue
        }
        val existing = map[boneName]
        if (existing != null) {
            existing[axis] += value
        } else {
            val arr = FloatArray(3)
            arr[axis] = value
            map[boneName] = arr
        }
    }

    return map
}

/**
 * 获取或构造 3D 网格对象
 */
internal fun PaintViewModel.getMeshForState(state: Model3DState): Mesh3D {
    val anglesKey = if (state.jointAngles.isEmpty()) "" else state.jointAngles.entries.sortedBy { it.key }.joinToString(",") { "${it.key}:${it.value}" }
    val key = "${state.modelType}#${state.posePreset}#$anglesKey"
    meshCache[key]?.let { return it }

    val skinned = getSkinnedModelForState(state)
    val mesh = if (skinned != null) {
        active3DSkeleton = skinned.skeleton
        val isCat = state.modelType.equals("cat", ignoreCase = true)
        val eulerMap = buildEulerMap(state.posePreset, state.jointAngles, isCat)
        val jointCount = skinned.skeleton?.count ?: 0
        val outJointPositions = if (jointCount > 0) FloatArray(jointCount * 3) else null
        val deformed = skinned.createDeformedMesh(eulerMap, outJointPositions)
        active3DWorldJoints = outJointPositions
        deformed
    } else {
        active3DSkeleton = null
        active3DWorldJoints = null
        val builtin = BuiltinModel3D.fromId(state.modelType)
        when (builtin) {
            BuiltinModel3D.ANIME_BOY, BuiltinModel3D.BODY_MANNEQUIN -> Mesh3D.createAnimeMannequin(isFemale = false, posePreset = state.posePreset, angles = state.jointAngles)
            BuiltinModel3D.ANIME_GIRL -> Mesh3D.createAnimeMannequin(isFemale = true, posePreset = state.posePreset, angles = state.jointAngles)
            BuiltinModel3D.CAT -> Mesh3D.createCatModel(posePreset = state.posePreset, angles = state.jointAngles)
            BuiltinModel3D.BODY_SIT -> Mesh3D.createAnimeMannequin(isFemale = false, posePreset = "sit", angles = state.jointAngles)
            BuiltinModel3D.HEAD_MANNEQUIN -> {
                try {
                    appContext.assets.open("3d_models/head_planar.glb").use { s ->
                        GlbParser.parse(s).baseMesh
                    }
                } catch (e: Exception) {
                    Mesh3D.createHeadMannequin()
                }
            }
            BuiltinModel3D.CUBE -> Mesh3D.createCube(1.8f)
            BuiltinModel3D.SPHERE -> Mesh3D.createSphere(1.2f, 18, 24)
            BuiltinModel3D.CYLINDER -> Mesh3D.createCylinder(1.0f, 2.0f, 24)
            null -> {
                val rawKey = state.modelType
                if (rawKey.startsWith("file:")) {
                    try {
                        val file = java.io.File(rawKey.substring(5))
                        if (file.exists()) {
                            file.inputStream().use { stream ->
                                Mesh3D.parseObj(BufferedReader(InputStreamReader(stream)))
                            }
                        } else {
                            Mesh3D.createCube(1.8f)
                        }
                    } catch (e: Exception) {
                        Log.e("RP_3D", "Failed to parse OBJ from file: $rawKey", e)
                        Mesh3D.createCube(1.8f)
                    }
                } else if (rawKey.startsWith("uri:")) {
                    try {
                        val uri = Uri.parse(rawKey.substring(4))
                        appContext.contentResolver.openInputStream(uri)?.use { stream ->
                            Mesh3D.parseObj(BufferedReader(InputStreamReader(stream)))
                        } ?: Mesh3D.createCube(1.8f)
                    } catch (e: Exception) {
                        Log.e("RP_3D", "Failed to parse OBJ from uri: $rawKey", e)
                        Mesh3D.createCube(1.8f)
                    }
                } else {
                    Mesh3D.createCube(1.8f)
                }
            }
        }
    }

    if (meshCache.size > 40) {
        meshCache.clear()
    }
    meshCache[key] = mesh
    return mesh
}

/**
 * 切换当前 3D 模型的姿态预设
 */
internal fun PaintViewModel.set3DPosePreset(preset: String) {
    val state = currentLayer3DState ?: return
    val updated = state.copy(posePreset = preset, jointAngles = emptyMap())
    update3DState(updated)
}

/**
 * 调整指定骨骼关节的微调角度
 */
internal fun PaintViewModel.set3DJointAngle(jointKey: String, angle: Float) {
    val state = currentLayer3DState ?: return
    val newAngles = state.jointAngles.toMutableMap()
    if (kotlin.math.abs(angle) < 0.01f) {
        newAngles.remove(jointKey)
    } else {
        newAngles[jointKey] = angle
    }
    val updated = state.copy(jointAngles = newAngles)
    update3DState(updated)
}

/**
 * 重置当前 3D 模型的骨骼微调角度
 */
internal fun PaintViewModel.reset3DJointAngles() {
    val state = currentLayer3DState ?: return
    val updated = state.copy(jointAngles = emptyMap())
    update3DState(updated)
}

/**
 * 新建内置 3D 参考图层
 */
internal fun PaintViewModel.create3DLayer(
    builtin: BuiltinModel3D = BuiltinModel3D.ANIME_BOY,
    customName: String? = null,
) {
    val displayName = customName ?: (getString(R.string.layer_3d_tag) + " (" + builtin.displayNameRes + ")")
    clearLayerSelection()

    runCore(after = {
        syncLayersFromNative()
        val curIdx = currentLayerIndex
        val layer = layers.getOrNull(curIdx)
        if (layer != null) {
            val initialState = Model3DState(
                layerId = layer.id,
                modelType = builtin.id,
                rotX = 18f,
                rotY = -35f,
                rotZ = 0f,
                panX = 0f,
                panY = 0f,
                zoom = 1.0f,
                lightYaw = 45f,
                lightPitch = 35f,
                renderMode = RenderMode3D.CLAY,
            )
            active3DLayers = active3DLayers + (layer.id to initialState)
            ensure3DLayerClearedInCore(layer.id)
            showActionToast(R.string.toast_3d_layer_created, R.drawable.ic_eye)
        }
    }) {
        ReverieCoreBridge.addLayer(displayName)
    }
}

/**
 * 导入外部 3D 模型 (.glb / .vrm / .pmx / .obj) 创建 3D 参考图层
 */
internal fun PaintViewModel.create3DLayerFromFile(
    uri: Uri,
    fileName: String,
) {
    val cleanName = fileName.replace(Regex("\\.(glb|vrm|pmx|obj)$", RegexOption.IGNORE_CASE), "")
    val displayName = getString(R.string.layer_3d_tag) + " (" + cleanName + ")"

    // 本地持久化缓存，防止设备重启或临时授权失效后丢失外部 3D 模型
    val modelsDir = java.io.File(appContext.filesDir, "3d_models").apply { mkdirs() }
    val safeFileName = "model_${System.currentTimeMillis()}_$fileName"
    val localFile = java.io.File(modelsDir, safeFileName)
    try {
        appContext.contentResolver.openInputStream(uri)?.use { input ->
            localFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
    } catch (e: Exception) {
        Log.e("RP_3D", "Failed to cache 3D model file to app internal storage", e)
    }

    val modelPathKey = if (localFile.exists() && localFile.length() > 0) {
        "file:${localFile.absolutePath}"
    } else {
        "uri:$uri"
    }

    try {
        val stream = if (localFile.exists() && localFile.length() > 0) {
            localFile.inputStream()
        } else {
            appContext.contentResolver.openInputStream(uri)
        }
        val lowerName = fileName.lowercase()
        stream?.use { s ->
            when {
                lowerName.endsWith(".glb") || lowerName.endsWith(".vrm") -> {
                    val skinned = GlbParser.parse(s)
                    skinnedModelCache[modelPathKey] = skinned
                }
                lowerName.endsWith(".pmx") -> {
                    val skinned = PmxParser.parse(s)
                    skinnedModelCache[modelPathKey] = skinned
                }
                else -> {
                    val mesh = Mesh3D.parseObj(BufferedReader(InputStreamReader(s)))
                    meshCache[modelPathKey] = mesh
                }
            }
        }
    } catch (e: Exception) {
        Log.e("RP_3D", "Failed to parse imported 3D model file", e)
        return
    }

    clearLayerSelection()
    runCore(after = {
        syncLayersFromNative()
        val curIdx = currentLayerIndex
        val layer = layers.getOrNull(curIdx)
        if (layer != null) {
            val initialState = Model3DState(
                layerId = layer.id,
                modelType = modelPathKey,
                rotX = 15f,
                rotY = -30f,
                zoom = 1.0f,
                renderMode = RenderMode3D.CLAY,
            )
            active3DLayers = active3DLayers + (layer.id to initialState)
            ensure3DLayerClearedInCore(layer.id)
            showActionToast(R.string.toast_3d_layer_created, R.drawable.ic_eye)
        }
    }) {
        ReverieCoreBridge.addLayer(displayName)
    }
}

/** 兼容旧接口别名 */
internal fun PaintViewModel.create3DLayerFromObj(uri: Uri, fileName: String) = create3DLayerFromFile(uri, fileName)

/**
 * 确保 3D 参考图层在底层 Krita 引擎中保持清空状态 (避免图层位图与实时硬件 Canvas 投影发生重影叠画)
 */
internal fun PaintViewModel.ensure3DLayerClearedInCore(layerId: Long) {
    val idx = layers.indexOfFirst { it.id == layerId }
    if (idx >= 0) {
        runCore(render = true, after = { notifyLayerChanged(forceThumbs = false) }) {
            ReverieCoreBridge.clearLayer(idx)
        }
    }
}

/**
 * 处理单指拖拽 3D 手势变换
 */
internal fun PaintViewModel.handle3DGesture(deltaX: Float, deltaY: Float) {
    val state = currentLayer3DState ?: return
    when (active3DInteractionMode) {
        Interaction3DMode.ROTATE -> {
            val newRotY = (state.rotY + deltaX * 0.45f) % 360f
            val newRotX = (state.rotX + deltaY * 0.45f).coerceIn(-89f, 89f)
            val updated = state.copy(rotX = newRotX, rotY = newRotY)
            update3DState(updated)
        }
        Interaction3DMode.PAN -> {
            val factor = 0.0035f / state.zoom.coerceAtLeast(0.1f)
            val newPanX = (state.panX + deltaX * factor).coerceIn(-5.0f, 5.0f)
            val newPanY = (state.panY - deltaY * factor).coerceIn(-5.0f, 5.0f)
            val updated = state.copy(panX = newPanX, panY = newPanY)
            update3DState(updated)
        }
        Interaction3DMode.LIGHT -> {
            val newYaw = (state.lightYaw + deltaX * 0.45f).coerceIn(-120f, 120f)
            val newPitch = (state.lightPitch - deltaY * 0.45f).coerceIn(-75f, 75f)
            val updated = state.copy(lightYaw = newYaw, lightPitch = newPitch)
            update3DState(updated)
        }
        Interaction3DMode.BONE -> {
            val jointName = active3DSelectedJointName
            if (jointName != null) {
                val yawKey = "${jointName}_y"
                val pitchKey = "${jointName}_p"
                val curYaw = state.jointAngles[yawKey] ?: 0f
                val curPitch = state.jointAngles[pitchKey] ?: 0f
                val newYaw = (curYaw + deltaX * 0.6f) % 360f
                val newPitch = (curPitch - deltaY * 0.6f).coerceIn(-120f, 120f)
                val newAngles = state.jointAngles.toMutableMap()
                newAngles[yawKey] = newYaw
                newAngles[pitchKey] = newPitch
                val updated = state.copy(jointAngles = newAngles)
                update3DState(updated)
            } else {
                // 未选中关节点时，滑动触摸空白处旋转模型整体视角
                val newRotY = (state.rotY + deltaX * 0.45f) % 360f
                val newRotX = (state.rotX + deltaY * 0.45f).coerceIn(-89f, 89f)
                val updated = state.copy(rotX = newRotX, rotY = newRotY)
                update3DState(updated)
            }
        }
    }
}

/**
 * 按比例缩放当前 3D 模型
 */
internal fun PaintViewModel.zoom3DModel(scaleRatio: Float) {
    val state = currentLayer3DState ?: return
    val newZoom = (state.zoom * scaleRatio).coerceIn(0.1f, 10.0f)
    val updated = state.copy(zoom = newZoom)
    update3DState(updated)
}

/**
 * 还原当前 3D 模型缩放到 100%
 */
internal fun PaintViewModel.reset3DZoom() {
    val state = currentLayer3DState ?: return
    val updated = state.copy(zoom = 1.0f)
    update3DState(updated)
}

/**
 * 更新 3D 状态 (仅更新轻量内存模型，驱动视口硬件 Canvas 120Hz 实时重绘)
 */
internal fun PaintViewModel.update3DState(updated: Model3DState) {
    active3DLayers = active3DLayers + (updated.layerId to updated)
    com.reverie.paint.ui.painting.canvas.CanvasTouchView.activeTouchView?.postInvalidate()
}

/**
 * 复位当前 3D 图层视角
 */
internal fun PaintViewModel.reset3DView() {
    val state = currentLayer3DState ?: return
    val reset = state.copy(
        rotX = 18f,
        rotY = -35f,
        rotZ = 0f,
        panX = 0f,
        panY = 0f,
        zoom = 1.0f,
        lightYaw = 45f,
        lightPitch = 35f,
    )
    update3DState(reset)
}

/**
 * 切换 3D 图层渲染风格 (素模 / 光影 / 线框)
 */
internal fun PaintViewModel.set3DRenderMode(mode: RenderMode3D) {
    val state = currentLayer3DState ?: return
    val updated = state.copy(renderMode = mode)
    update3DState(updated)
}

/**
 * 异步将 3D 烘焙结果写回底层 Krita 颜料图层 (在工作线程渲染，彻底杜绝主线程阻塞)
 */
internal fun PaintViewModel.commit3DLayerPixels(layerId: Long) {
    val state = active3DLayers[layerId] ?: return
    val layerIdx = layers.indexOfFirst { it.id == layerId }
    if (layerIdx < 0) return

    viewModelScope.launch(kotlinx.coroutines.Dispatchers.Default) {
        val mesh = getMeshForState(state)
        val w = docWidth.coerceAtLeast(1)
        val h = docHeight.coerceAtLeast(1)

        val rendered = gl3dRenderer.renderToBitmap(mesh, state, w, h) ?: return@launch
        val stampBmp = ImageImportHelper.swapRedAndBlueForStamp(rendered)

        runCore(render = true, after = {
            notifyLayerChanged(forceThumbs = false)
        }) {
            val targetIdx = layers.indexOfFirst { it.id == layerId }
            if (targetIdx >= 0) {
                val cur = ReverieCoreBridge.currentLayerIndex()
                ReverieCoreBridge.setCurrentLayer(targetIdx)
                ReverieCoreBridge.clearLayer(targetIdx)
                ReverieCoreBridge.stampBitmap(0, 0, stampBmp)
                ReverieCoreBridge.setCurrentLayer(cur)
            }
            stampBmp.recycle()
            if (rendered !== stampBmp && !rendered.isRecycled) {
                rendered.recycle()
            }
        }
    }
}

/**
 * 栅格化当前 3D 图层 (将其转化为普通光栅像素图层，解除 3D 绑定)
 */
internal fun PaintViewModel.rasterize3DLayer(layerId: Long) {
    commit3DLayerPixels(layerId)
    active3DLayers = active3DLayers - layerId
    live3DPreviewBitmap = null
    showActionToast(R.string.toast_3d_rasterized, R.drawable.ic_layers)
}

/**
 * 同步将所有活跃 3D 参考图层渲染并烘焙至底层 Krita 颜料图层 (在导出与持久化前调用)
 */
internal fun PaintViewModel.commitAllActive3DLayersSync() {
    for ((layerId, state) in active3DLayers) {
        val layerIdx = layers.indexOfFirst { it.id == layerId }
        if (layerIdx >= 0) {
            val mesh = getMeshForState(state)
            val w = docWidth.coerceAtLeast(1)
            val h = docHeight.coerceAtLeast(1)
            val rendered = gl3dRenderer.renderToBitmap(mesh, state, w, h)
            if (rendered != null) {
                val stampBmp = ImageImportHelper.swapRedAndBlueForStamp(rendered)
                ReverieCoreBridge.clearLayer(layerIdx)
                val cur = ReverieCoreBridge.currentLayerIndex()
                ReverieCoreBridge.setCurrentLayer(layerIdx)
                ReverieCoreBridge.stampBitmap(0, 0, stampBmp)
                ReverieCoreBridge.setCurrentLayer(cur)
                stampBmp.recycle()
                if (rendered !== stampBmp && !rendered.isRecycled) {
                    rendered.recycle()
                }
            }
        }
    }
}

/**
 * 提取当前 3D 图层的轮廓线稿，在新图层中创建线稿层
 */
internal fun PaintViewModel.extract3DOutlines(layerId: Long) {
    val state = active3DLayers[layerId] ?: return
    val mesh = getMeshForState(state)
    val wireState = state.copy(renderMode = RenderMode3D.WIREFRAME, wireframeColor = 0xFF1A1A1AL)

    val w = docWidth.coerceAtLeast(1)
    val h = docHeight.coerceAtLeast(1)
    val rendered = gl3dRenderer.renderToBitmap(mesh, wireState, w, h) ?: return
    val stampBmp = ImageImportHelper.swapRedAndBlueForStamp(rendered)

    val outlineLayerName = getString(R.string.layer_3d_extract_outline)
    runCore(render = true, after = {
        syncLayersFromNative()
        showActionToast(R.string.toast_3d_outline_extracted, R.drawable.ic_magicwand)
    }) {
        ReverieCoreBridge.addLayer(outlineLayerName)
        ReverieCoreBridge.stampBitmap(0, 0, stampBmp)
        stampBmp.recycle()
        if (rendered !== stampBmp && !rendered.isRecycled) {
            rendered.recycle()
        }
    }
}
