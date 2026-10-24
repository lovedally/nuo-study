// 语文 · 内容层暂缓（三年级内容未建）。
//
// 内容形态决定了这个模块的重心：**朗读/背诵 + 字词默写 + 阅读**。
// 作文（习作）属于半结构化，只能落成打卡 + 拍照上传，不要指望自动判分。
//
// 朗读需要录音权限 —— 这是孩子端唯一需要申请的敏感权限，且要按需申请
// （进入朗读题时才弹，不要一启动就弹）。
plugins {
    id("app.android.library")
    id("app.android.compose")
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.nuostudy.feature.chinese"
}

dependencies {
    implementation(project(":core-ui"))
    implementation(project(":core-data"))
    implementation(project(":core-sync"))

    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
}
