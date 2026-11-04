# Upstream license sources

These texts are retained from the official upstream projects. Per-artifact
LICENSE/NOTICE files are additionally copied verbatim from the actual resolved
AAR/JAR by `app/open-source.gradle`.

| Local file | Original source |
| --- | --- |
| AndroidX-LICENSE.txt | https://raw.githubusercontent.com/androidx/androidx/androidx-main/LICENSE.txt |
| AndroidX-NOTICE.txt | Attribution summary; original AAR/JAR notices are retained separately |
| AndroidX-Graphics-Path-NOTICE.txt | Original native source notices at the release revision stated in this file |
| Kotlin-LICENSE.txt | https://raw.githubusercontent.com/JetBrains/kotlin/v2.2.21/license/LICENSE.txt |
| Kotlin-NOTICE.txt | https://raw.githubusercontent.com/JetBrains/kotlin/v2.2.21/license/NOTICE.txt (upstream distribution NOTICE; Kotlin compiler itself is a build tool) |
| Coroutines-LICENSE.txt | https://raw.githubusercontent.com/Kotlin/kotlinx.coroutines/1.10.2/LICENSE.txt |
| Serialization-LICENSE.txt | https://raw.githubusercontent.com/Kotlin/kotlinx.serialization/v1.7.3/LICENSE.txt |
| JetBrains-Annotations-LICENSE.txt | https://raw.githubusercontent.com/JetBrains/java-annotations/23.0.0/LICENSE.txt |
| JSpecify-LICENSE.txt | https://raw.githubusercontent.com/jspecify/jspecify/v1.0.0/LICENSE |
| Guava-NOTICE.txt | Copyright/license header of ListenableFuture.java, source link retained in file |

The repository root LICENSE is the unmodified official text downloaded from
https://www.apache.org/licenses/LICENSE-2.0.txt. AndroidX's root text is generic;
exact artifact versions are pinned in `../open-source-components.json`, and
license texts carried by those artifacts take precedence for those components.
