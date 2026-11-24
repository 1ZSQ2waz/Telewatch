/*
 * Copyright (c) 2024 gohj99. Lorem ipsum dolor sit amet, consectetur adipiscing elit.
 * Morbi non lorem porttitor neque feugiat blandit. Ut vitae ipsum eget quam lacinia accumsan.
 * Etiam sed turpis ac ipsum condimentum fringilla. Maecenas magna.
 * Proin dapibus sapien vel ante. Aliquam erat volutpat. Pellentesque sagittis ligula eget metus.
 * Vestibulum commodo. Ut rhoncus gravida arcu.
 */

package com.gohj99.telewatch.utils

import android.app.Application
import com.gohj99.telewatch.R
import com.gohj99.telewatch.loadConfig
import org.acra.BuildConfig
import org.acra.config.httpSender
import org.acra.config.toast
import org.acra.data.StringFormat
import org.acra.ktx.initAcra
import org.acra.sender.HttpSender

class ACRA : Application() {

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(WatchActivityPolicy)

        // 获取是否同意获取数据
        val settingsSharedPref = getSharedPreferences("app_settings", MODE_PRIVATE)
        val dataCollection = settingsSharedPref.getBoolean("Data_Collection", false)

        if (dataCollection) {
            val config = loadConfig(this)
            val acraBasicAuthLogin = config.getProperty("acra_basic_auth_login").orEmpty()
            val acraBasicAuthPassword = config.getProperty("acra_basic_auth_password").orEmpty()

            // 初始化 com.gohj99.telewatch.utils.ACRA 配置

            initAcra {
                // 核心配置
                buildConfigClass = BuildConfig::class.java
                reportFormat = StringFormat.JSON

                // 凭据由构建配置提供；缺少凭据时不启用 HTTP 上报。
                if (acraBasicAuthLogin.isNotEmpty() && acraBasicAuthPassword.isNotEmpty()) {
                    httpSender {
                        uri = "https://acra.gohj99.site/report"
                        httpMethod = HttpSender.Method.POST
                        basicAuthLogin = acraBasicAuthLogin
                        basicAuthPassword = acraBasicAuthPassword
                    }
                }

                // 配置 Toast 插件
                toast {
                    text = getString(R.string.acra_toast_text)
                    // 打开这个块自动启用插件
                }
            }
        }
    }
}
