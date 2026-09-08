package com.smartledger.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smartledger.ui.theme.SmartLedgerColors

@Composable
fun PrivacyPolicyScreen(
    onBack: () -> Unit = {}
) {
    Box(modifier = Modifier.fillMaxSize().background(SmartLedgerColors.bg)) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 顶部栏
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Outlined.ArrowBack, contentDescription = "返回", tint = SmartLedgerColors.fg)
                }
                Text(
                    text = "隐私政策",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = SmartLedgerColors.fg
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
            ) {
                // 更新日期
                Text(
                    text = "更新日期：2026年9月7日",
                    style = MaterialTheme.typography.bodySmall,
                    color = SmartLedgerColors.fgSecondary
                )
                Text(
                    text = "生效日期：2026年9月7日",
                    style = MaterialTheme.typography.bodySmall,
                    color = SmartLedgerColors.fgSecondary
                )

                Spacer(modifier = Modifier.height(20.dp))

                PolicySection("一、引言") {
                    append("SmartLedger（以下简称'我们'或'本应用'）是一款个人记账应用，致力于帮助用户轻松记录和管理日常收支。我们深知个人信息对您的重要性，将严格遵守相关法律法规，采取相应的安全保护措施来保护您的个人信息。请您在使用本应用前，仔细阅读并充分理解本政策。")
                    appendLine()
                    appendLine()
                    append("【本次修订重点】新增「AI 财务顾问」可选功能的相关说明（见第四条），并相应修正了关于本地存储与第三方服务的表述。该功能默认关闭，需要您自行填写第三方模型服务商的 API Key 才能启用；不启用时，本应用的全部数据处理行为与修订前完全一致。")
                }

                PolicySection("二、信息收集与使用") {
                    appendLine("本应用在运行过程中，可能涉及以下信息的收集和使用：")
                    appendLine()
                    appendLine("1. 通知访问权限：本应用通过 Android 系统的 NotificationListenerService 监听支付类应用（如微信、支付宝、云闪付、银行App等）的通知消息，自动识别交易金额、商户名称和支付方式。此功能仅用于自动记账，不会上传或存储您的通知原文。")
                    appendLine()
                    appendLine("2. 悬浮窗权限：用于在检测到支付行为时弹出确认窗口，方便用户快速确认记账。")
                    appendLine()
                    appendLine("3. 存储权限：用于数据备份和导出功能，将您的记账数据以 CSV 格式保存到设备本地存储（Documents/SmartLedger/ 目录）。")
                    appendLine()
                    appendLine("4. 电池优化豁免：为确保后台监听服务的持续运行，本应用可能请求关闭电池优化。")
                }

                PolicySection("三、信息存储与安全") {
                    appendLine("1. 本地存储：您的记账数据（账单、分类、预算）存储在您的设备本地数据库中。除下述第（四）条所述的、由您主动启用的 AI 功能外，本应用不会将其上传至任何服务器。")
                    appendLine()
                    appendLine("2. 数据备份：备份文件保存在您设备的 Documents/SmartLedger/ 目录下，由您自行管理。")
                    appendLine()
                    appendLine("3. 数据安全：我们采用行业通用的安全措施保护您存储在设备上的数据。AI 服务商的 API Key 使用 Android 系统密钥库（AndroidKeyStore）加密后仅存储于本机，不会写入任何日志，并且已从系统自动备份与换机迁移中排除。")
                }

                PolicySection("四、AI 财务顾问（可选功能）") {
                    appendLine("本应用提供两项基于大语言模型的可选功能：「AI 消费体检」（统计页）与「自然语言/语音快捷记账」（记账页）。关于这两项功能，请您特别注意：")
                    appendLine()
                    appendLine("1. 默认关闭。您需要自行在「设置 → AI 财务顾问」中填写第三方模型服务商（如 DeepSeek）的 Base URL、API Key 与模型名，功能才会启用。未配置时，相关入口不会显示，本应用完全离线运行。")
                    appendLine()
                    appendLine("2. 仅在您主动点击时联网。打开页面、切换标签、新增账单、后台任务均不会触发任何 AI 请求。")
                    appendLine()
                    appendLine("3. 直连您的服务商，我们不经手数据。请求由您的设备直接发送到您配置的服务商地址。本应用不设中转服务器，不收集、不留存、不转发您的任何数据，也无法看到您与服务商之间的通信内容。")
                    appendLine()
                    appendLine("4. 会发送的内容（仅限本地统计结果）：周期标签；收入/支出/预算的金额合计；分类名称与对应金额、笔数、占比；脱敏后的商户编号（如「餐饮商户 #1」）及其金额与笔数；时段、工作日/周末、夜间的金额与笔数分布；交易总笔数、日均与环比百分比。")
                    appendLine()
                    appendLine("5. 不会发送的内容：原始支付通知全文、标题与来源包名；银行卡号与卡尾号；手机号；订单号与支付流水号；真实商户名称（含任何片段）；备注原文；联系人姓名；逐笔交易的精确时间序列。上述限制在代码层面通过数据结构隔离实现——用于组装请求的数据结构中不存在可承载这些信息的字段。")
                    appendLine()
                    appendLine("6. 语音输入。语音记账调用 Android 系统的语音识别服务（ACTION_RECOGNIZE_SPEECH），本应用不申请麦克风权限、不录制音频、不上传任何音频文件，仅接收系统返回的文字结果。语音识别过程受您设备上系统语音服务提供方的隐私政策约束。")
                    appendLine()
                    appendLine("7. AI 不会自动改动您的数据。AI 消费体检给出的「建议可用总额度」仅供展示，必须经您在弹窗中二次确认（且可修改金额）后才会写入预算；自然语言记账的解析结果仅回填表单，必须由您检查并点击「记一笔」后才会入库。AI 不具备任何自动记账、自动改分类或自动调预算的能力。")
                    appendLine()
                    appendLine("8. AI 生成内容仅供参考，不构成投资、理财、税务或法律建议。")
                    appendLine()
                    appendLine("9. 第三方服务商的条款。您配置的服务商（如 DeepSeek）如何处理其收到的请求内容，取决于您与该服务商之间的协议及其隐私政策，建议您在使用前自行查阅。本应用不对其行为负责，也无法代其作出承诺。")
                    appendLine()
                    appendLine("10. 关闭与清除。您可以随时在「设置 → AI 财务顾问」中清除配置，该操作会同时删除 API Key 与本地缓存的全部 AI 报告，且不影响您的记账数据。")
                }

                PolicySection("五、信息共享与披露") {
                    appendLine("除下述第（四）条所述的、由您主动启用并直接对接第三方模型服务商的 AI 功能外，我们不会将您的个人信息共享、转让或披露给任何第三方，但以下情况除外：")
                    appendLine()
                    appendLine("1. 获得您的明确同意或授权；")
                    appendLine("2. 根据适用的法律法规、法律程序或政府机关的强制性要求；")
                    appendLine("3. 为维护本应用的合法权益所合理必需的情况。")
                }

                PolicySection("六、第三方服务") {
                    appendLine("本应用不集成任何第三方 SDK、广告插件或数据分析工具，不含任何统计埋点。支付通知监听、语音识别等功能完全基于 Android 系统原生 API。")
                    appendLine()
                    appendLine("唯一涉及第三方的情形是您主动启用的 AI 财务顾问功能：该功能不含我们的任何服务端组件，是您的设备与您自行选择的模型服务商之间的直接通信。我们不参与、不代理、不记录这一过程。")
                    appendLine()
                    appendLine("应用内检查更新功能会访问 GitHub 的公开 API 以获取版本信息，该请求不携带任何您的个人数据或记账数据。")
                }

                PolicySection("七、您的权利") {
                    appendLine("您对您的个人信息享有以下权利：")
                    appendLine()
                    appendLine("1. 查看权：您可以随时在本应用中查看您的所有记账数据。")
                    appendLine("2. 删除权：您可以删除单条记录、分类或清空所有数据。")
                    appendLine("3. 导出权：您可以随时将数据导出为 CSV 格式文件。")
                    appendLine("4. 备份与恢复：您可以通过备份功能保存数据，并在需要时恢复。")
                    appendLine("5. 权限管理：您可以在系统设置中随时关闭本应用的各项权限。")
                    appendLine("6. 关闭 AI 功能：您可以随时清除 AI 配置与本地 AI 报告缓存，撤回对该功能的启用。")
                }

                PolicySection("八、未成年人保护") {
                    append("我们非常重视对未成年人个人信息的保护。如果您是18周岁以下的未成年人，请在您的监护人的陪同下仔细阅读本政策，并在征得您的监护人的同意后使用本应用。鉴于 AI 财务顾问功能会将部分统计数据发送至您自行选择的第三方服务商，我们建议未成年人在监护人指导下使用该功能。")
                }

                PolicySection("九、政策更新") {
                    append("我们可能会适时修订本政策的条款，该等修订构成本政策的一部分。如修订造成您在本政策下权利的实质减少，我们将在修订生效前通过应用内通知的方式通知您。首次启用 AI 功能时，本应用会单独弹窗向您明示该功能的数据处理范围，需您确认后方可使用。")
                }

                PolicySection("十、联系我们") {
                    append("如您对本政策有任何疑问、意见或建议，您可以通过以下方式与我们联系：\n\n邮箱：joah45@qq.com\n\n我们将在15个工作日内回复您的请求。")
                }

                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

@Composable
private fun PolicySection(title: String, contentBuilder: StringBuilder.() -> Unit) {
    val content = remember(title) {
        StringBuilder().apply(contentBuilder).toString()
    }

    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = SmartLedgerColors.fg,
        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
    )
    Text(
        text = content,
        style = MaterialTheme.typography.bodyMedium,
        color = SmartLedgerColors.fgSecondary,
        lineHeight = 22.sp
    )
}
