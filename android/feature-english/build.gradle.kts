// 英语 · 三年级：四大时态、词汇、句型。
//
// ⚠️ 拼写题**必须用自绘键盘**。系统输入法的自动纠错/联想会替孩子把单词拼对，
//    这样一来「默写」就完全失去意义 —— 这是本模块最容易做错的一处。
//
// 跟读评测属于 M4（AI 旁路服务），当前阶段录音只做留存，不做评分。
plugins {
    id("app.android.library")
    id("app.android.compose")
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.nuostudy.feature.english"
}

dependencies {
    implementation(project(":core-ui"))
    implementation(project(":core-data"))
    implementation(project(":core-sync"))
    // 英语能力的接口（EnglishApi）。core-data 已经 api 暴露了 core-network，
    // 这里仍显式写一行：本模块**直接用**了它的类型，靠传递依赖拿到属于运气。
    implementation(project(":core-network"))

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    // hiltViewModel() —— 英语页要拿服务端地址与 TTS 能力，见 EnglishViewModel。
    implementation(libs.hilt.navigation.compose)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
}
