// 数学 · 内容层暂缓（三年级内容未建）。
//
// 这是三科里**最适合做自动判分**的一科（纯算式可精确求值），
// 但本项目 M2 已整体暂缓，所以这里的作答目前只落库、不判对错。
//
// ⚠️ 竖式计算必须手写：answer_mode = 2，走自绘手写画布，
//    且作答应允许多行（不能只给一行输入框）。
plugins {
    id("app.android.library")
    id("app.android.compose")
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.nuostudy.feature.math"
}

dependencies {
    implementation(project(":core-ui"))
    implementation(project(":core-data"))
    implementation(project(":core-sync"))

    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
}
