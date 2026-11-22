# 第三方数据清单

更新时间：2026-11-15

## 拼音词库

| 数据 | 来源与固定版本 | 用途 | 许可与处理 |
|---|---|---|---|
| CC-CEDICT | MDBG 发布；源文件头声明 CEDICT Copyright (C) 1997, 1998 Paul Andrew Denisowski；gzip SHA-256 `82652957FA7C459ECC7B0D5E3F4CC2E3C0C96A29F950E7192BAAE31575469C94` | 提供简体词条与带声调拼音 | 源文件标注 CC BY-SA 4.0；派生词库按 CC BY-SA 4.0 发布，并注明过滤、拼音规范化、频次合并及权重计算修改。为避免未确认 MDBG 自动访问规则，重建脚本不再抓取该站；操作者应通过获准方式取得文件并校验固定哈希 |
| Jieba 词频表 | `fxsjy/jieba` commit `67fa2e36e72f69d9134b8a1037b83fbb070b9775` 的 `jieba/dict.txt`；SHA-256 `7197C3211DDD98962B036CDF40324D1EA2BFAA12BD028E68FAA70111A88E12A8`；原始文件约 349,046 行，过滤后识别 348,974 个汉字词条 | 为 CC-CEDICT 拼音词条提供通用词频排序 | 上游 README 与 LICENSE 声明 Jieba 项目采用 MIT；APK NOTICE 保留版权和完整 MIT 文本。`dict.txt` 未单独注明底层语料来源/许可，公开分发前仍需核验其数据权利 |
| THUOCL | `thunlp/THUOCL` commit `a30ce79d895d01ab5132a5c74c29703ff7efb4cc`；ZIP SHA-256 `0C710AEAF9B75B345C944C3C4F625BB4C8135EE97518DC5BE5F444BF5ED1F89B`；领域词表约 10.8 万个不同词 | 补充领域词文档频次，参与候选排序 | 仓库含 MIT LICENSE，完整文本已加入 APK NOTICE。README 另允许研究与商业使用，并提出研究引用/致谢要求。词表与 DF 上游语料来源尚未完全明晰，发布前仍需核验 |
| 历史主词库种子 | Agent Keyboard commit `e33bc8f` 的 3,358 行 `pinyin_dict.txt` | 仅用于同解码器 A/B 对照 | 当前生成器和 APK 均不再读取、合并或保留这份历史词表；不作为运行时或构建期兜底 |

## 生成结果

- 生成器：[generate_pinyin_dict.mjs](../../tools/generate_pinyin_dict.mjs)
- 来源获取与重建：[prepare_pinyin_sources.ps1](../../tools/prepare_pinyin_sources.ps1)
- 运行时资产：`android/app/src/main/assets/pinyin_dict.txt`
- 当前规模：110,147 个拼音-词条目、110,057 个不同词形、2,524,650 字节；每行为 `拼音<TAB>词<TAB>权重`。
- 词条由 CC-CEDICT 拼音映射 Jieba 词频；THUOCL 同词多领域 DF 取最大值。权重为 `floor(log2(jieba_frequency + 1) * 1000 + log2(thuocl_df + 1) * 100)`。
- 当前 79,175 个条目有 Jieba 词频，12,646 个条目有 THUOCL DF；两种权重有交叠，合计 80,614 个条目权重非零。剩余零权重词仍保留在全量 CC-CEDICT 映射中，但不因此形成独立备用词库。
- 全部词条离线内置；键盘查询不联网，也不下载词库。重建工具只在构建期获取固定版本的数据。
- 同解码器、同一 11 条固定语料 A/B：原 3,358 条词库为 Top-1/Top-5 **4/11、4/11**；新词库为 **4/11、4/11**。新表保留原 4 个命中，但没有提升该语料的总命中率。候选质量不能由词条规模或频词来源单独证明。

Google Books Ngram 简体中文 2020 unigram 曾作为候选排序源进行本地 PoC；源文件 SHA-256 `6AB29862A8C904C261BB9D5A02A342285C4DBABF79CEE5C4F8818076FD44FB86`，按 2000 年起计数后固定语料为 Top-1/Top-5 **3/11、4/11**，低于 Jieba 排序版，故没有写入 APK 或最终生成器。官方索引页将该编译数据标为 CC BY 3.0。SUBTLEX-CH 的再分发权限未确认，也未纳入构建。

## 已评估但未采用：Fcitx 拼音词典

`fcitx/libime` 的 [`data/CMakeLists.txt`](https://github.com/fcitx/libime/blob/53df6a248793f9a00c1769e658630de313f3e419/data/CMakeLists.txt) 固定词典包 `dict-20260907.tar.zst`，SHA-256 为 `FB75A179065E690DFC4559CE1807CBAF4FBE4F0111A5005615BE9F435E6B9D76`。同一上游提交的 [`REUSE.toml`](https://github.com/fcitx/libime/blob/53df6a248793f9a00c1769e658630de313f3e419/REUSE.toml) 将该包标注为 LGPL-2.1-or-later，版权归属列为 Yuking (2002–2005) 与 CSSlayer (2014–2026)。包内 `dict_sc.txt` 有 300,624 行、约 198,005 个不同词形、205,354 个拼音-词对；格式为 `词<TAB>拼音音节<TAB>可选权重`。

评估结论：覆盖量有吸引力，但约 99.8% 行没有有效正排序权重，且包内含繁体/异体字；当前项目也有 17 种 Fcitx 拼音音节不在 `pinyin_chars.txt`。上游另提供独立简体 ARPA 语言模型，但那是 libime 的模型输入，不是可直接映射为本项目每个词的权重。因此，单独换入 `dict_sc.txt` 不能推断候选质量变好。本批没有引入它；后续若评估，必须先做简体筛选、音节与权重转换，再与当前解码器跑同一固定语料 A/B 和性能测试。该许可适用于上游数据包，但公开分发派生资源时仍需在 APK NOTICE 中保留 LGPL 条款与修改说明。

重建命令：

```powershell
$sourceRoot = Join-Path $PWD "android\app\build\pinyin-sources"
.\tools\prepare_pinyin_sources.ps1 -CedictArchivePath (Join-Path $sourceRoot "cedict.txt.gz")
```

将通过获准方式取得的 CC-CEDICT gzip 放入上述位置；脚本校验固定 SHA-256 后才解压。Jieba、THUOCL 固定版本由脚本获取并校验哈希。脚本不会自动请求 MDBG。词库重建需要 Windows PowerShell 与 Node.js；Android 运行时不下载这些来源。变更数据源、过滤或评分规则后，必须运行 `PinyinDecoderTest`、`PinyinBenchmarkTest`、全量 `testDebugUnitTest` 和 `assembleDebug`。

## 固定语料 A/B 复现

历史 3,358 条词库以精确 Git blob 字节保存在 `android/app/src/test/resources/pinyin-dictionary-ab-baseline.tsv`，SHA-256 为 `0D3BBA7A5C9229B47531C4FD38DB04B33588997F1B23C67144EEC785F680F5F3`。它只用于同一解码器的离线质量对照，不是候选回退或生产词库，不参与生成器、运行时代码或 APK 资产。

```powershell
cd android
.\gradlew.bat testDebugUnitTest --tests com.agentkeyboard.ime.pinyin.PinyinBenchmarkTest --no-daemon --console=plain --info
```

Gradle test report XML/HTML 中的 `PINYIN_BENCH_SUMMARY` 是新词库结果，`PINYIN_COMPARE_SUMMARY` 是测试专用旧词库结果。发布包隔离检查为 `.\gradlew.bat verifyReleasePinyinDictionaryPackaging`，确认新词库存在且旧测试基准未进入 Release APK。

## 后续待办

- 扩大拼音质量语料，覆盖真实聊天、长句、姓名、数字、中英混输和专业词，并以 Top-1/Top-5、候选操作数和错误样本分布衡量。
- 当前词库没有整句语言模型、拼写纠错或上下文预测；Jieba 词频只是通用文本权重，不是商业输入法级候选质量证明。
- 公开分发前继续核对 CC BY-SA 4.0、Jieba MIT 和 THUOCL 的署名及数据再分发义务。
