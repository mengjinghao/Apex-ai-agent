package com.apex.ui.mascot

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.ai.assistance.aiterminal.terminal.mascot.AuraMascot

/**
 * Aura 水母 Compose 视图 — 帧动画版(app 层)。
 *
 * 渲染当前形态对应的 [android.graphics.drawable.AnimationDrawable](4 帧循环 PNG),带:
 *  - 帧动画播放(每形态 4 帧,由 res/drawable/aura_anim_<form>.xml 定义)
 *  - 轻微浮动 + 呼吸缩放(基础动画)
 *  - 形态切换时的变身过渡特效(淡入淡出 + 缩放 + 光环爆发)
 *
 * # 使用示例
 *
 * ```
 * val controller = integration.getController(sessionId)
 * val state by controller.state.collectAsState()
 *
 * AuraMascotView(
 *     form = state.form,
 *     modifier = Modifier.size(200.dp),
 *     transitionEnabled = true   // 启用变身特效
 * )
 * ```
 *
 * @param form 当前形态
 * @param modifier 布局修饰符
 * @param sizePx 显示尺寸(dp)
 * @param transitionEnabled 形态切换时是否播放变身过渡特效
 */
@Composable
fun AuraMascotView(
    form: AuraMascot.AuraForm,
    modifier: Modifier = Modifier,
    sizePx: Dp = 200.dp,
    transitionEnabled: Boolean = true,
) {
    val accent = accentColorFor(form)

    // 浮动动画:轻微上下浮动(呼吸感)
    val infiniteTransition = rememberInfiniteTransition(label = "aura_float")
    val floatY by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = -6f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000),
            repeatMode = RepeatMode.Reverse
        ),
        label = "aura_float_y"
    )
    // 呼吸缩放
    val breathScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(
            animation = tween(3600),
            repeatMode = RepeatMode.Reverse
        ),
        label = "aura_breath"
    )

    Box(
        modifier = modifier.size(sizePx),
        contentAlignment = Alignment.Center
    ) {
        // 形态切换变身光环爆发特效
        if (transitionEnabled) {
            TransitionBurstEffect(
                form = form,
                accent = accent,
                modifier = Modifier.fillMaxSize()
            )
        }

        // 主水母图像 + 变身过渡
        AnimatedContent(
            targetState = form,
            transitionSpec = {
                if (transitionEnabled) {
                    // 变身过渡:旧形态放大淡出 + 新形态缩小淡入(像爆炸重组)
                    (scaleIn(initialScale = 0.3f, animationSpec = tween(500)) +
                        fadeIn(animationSpec = tween(500)))
                        .togetherWith(
                            scaleOut(targetScale = 1.8f, animationSpec = tween(500)) +
                                fadeOut(animationSpec = tween(500))
                        )
                } else {
                    fadeIn(tween(150)).togetherWith(fadeOut(tween(150)))
                }
            },
            label = "aura_form_transition"
        ) { currentForm ->
            JellyfishFrame(
                form = currentForm,
                accent = accentColorFor(currentForm),
                floatY = floatY,
                breathScale = breathScale,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

/**
 * 单帧水母渲染 — 加载 AnimationDrawable 并播放。
 *
 * 优先用 AndroidView + ImageView 播放 AnimationDrawable(完整 4 帧循环),
 * 资源缺失时降级到静态 PNG 或 emoji。
 */
@Composable
private fun JellyfishFrame(
    form: AuraMascot.AuraForm,
    accent: Color,
    floatY: Float,
    breathScale: Float,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    val animDrawableName = AuraMascot.getAnimationDrawableName(form)
    val animResId = remember(animDrawableName) {
        context.resources.getIdentifier(animDrawableName, "drawable", context.packageName)
    }
    val staticDrawableName = AuraMascot.getDrawableName(form)
    val staticResId = remember(staticDrawableName) {
        context.resources.getIdentifier(staticDrawableName, "drawable", context.packageName)
    }

    val graphicsModifier = modifier.graphicsLayer {
        translationY = floatY
        scaleX = breathScale
        scaleY = breathScale
    }

    when {
        animResId != 0 -> {
            // 帧动画版 — AndroidView 桥接 ImageView 播放 AnimationDrawable
            AndroidView(
                factory = { ctx ->
                    android.widget.ImageView(ctx).apply {
                        scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                        val drawable = androidx.core.content.ContextCompat.getDrawable(ctx, animResId)
                            as? android.graphics.drawable.AnimationDrawable
                        setImageDrawable(drawable)
                        drawable?.start()
                    }
                },
                update = { imageView ->
                    (imageView.drawable as? android.graphics.drawable.AnimationDrawable)?.let { ad ->
                        if (!ad.isRunning) ad.start()
                    }
                },
                modifier = graphicsModifier
            )
        }
        staticResId != 0 -> {
            Image(
                painter = painterResource(id = staticResId),
                contentDescription = "Aura — ${AuraMascot.getEmoji(form)} ${form.displayName}",
                modifier = graphicsModifier,
                alpha = 0.95f
            )
        }
        else -> {
            BasicText(
                text = "${AuraMascot.getEmoji(form)}\n${form.displayName}",
                modifier = modifier.graphicsLayer { translationY = floatY },
                style = TextStyle(
                    color = accent,
                    fontSize = TextUnit(14f, TextUnitType.Sp),
                    textAlign = TextAlign.Center
                )
            )
        }
    }
}

/**
 * 变身光环爆发特效。
 *
 * 形态切换时,从水母中心向外扩散一个 accent 色光环(缩放 0.3→1.8,透明度 0.9→0),
 * 配合主图像的 scaleIn/scaleOut 形成爆炸重组的变身效果。
 */
@Composable
private fun TransitionBurstEffect(
    form: AuraMascot.AuraForm,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    var burstScale by remember { mutableStateOf(0.3f) }
    var burstAlpha by remember { mutableStateOf(0f) }

    LaunchedEffect(form) {
        // 触发爆发:从 0.3 缩放到 1.8,透明度从 0.9 衰减到 0
        burstScale = 0.3f
        burstAlpha = 0.9f
        val steps = 30
        for (i in 1..steps) {
            val t = i.toFloat() / steps
            burstScale = 0.3f + 1.5f * t
            burstAlpha = 0.9f * (1f - t)
            kotlinx.coroutines.delay(16)
        }
        burstAlpha = 0f
    }

    if (burstAlpha > 0.01f) {
        Box(
            modifier = modifier,
            contentAlignment = Alignment.Center
        ) {
            // 光环:accent 色圆形描边,缩放扩散
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .scale(burstScale)
                    .alpha(burstAlpha)
                    .graphicsLayer {
                        // 用阴影模拟光环发光
                        shadowElevation = 20f
                        shape = androidx.compose.ui.graphics.CircleShape
                        clip = false
                    }
            ) {
                // 内部填充极淡 accent 色
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .alpha(0.3f)
                )
            }
        }
    }
}

/**
 * 形态对应的 accent 色(深海极光配色,与 ai-terminal 的 ApexTerminalTheme 对齐)。
 */
private fun accentColorFor(form: AuraMascot.AuraForm): Color = when (AuraMascot.getAccent(form)) {
    AuraMascot.AuraAccent.CYAN -> Color(0xFF00E5FF)   // 电光青
    AuraMascot.AuraAccent.PINK -> Color(0xFFFF6B9D)   // 珊瑚粉
    AuraMascot.AuraAccent.AMBER -> Color(0xFFFBBF24)  // 琥珀金
    AuraMascot.AuraAccent.MINT -> Color(0xFF4ADE80)   // 薄荷绿
    AuraMascot.AuraAccent.ROSE -> Color(0xFFEF4444)   // 玫瑰红
    AuraMascot.AuraAccent.VIOLET -> Color(0xFFA78BFA) // 紫罗兰
    AuraMascot.AuraAccent.SKY -> Color(0xFF60A5FA)    // 天空蓝
}
