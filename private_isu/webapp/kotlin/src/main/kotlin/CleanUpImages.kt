package io.github.msksgm

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory

const val ImageDir = "/home/isucon/private_isu/webapp/public/image"

internal fun cleanupImages() {
    val targetDir = Path.of(ImageDir)

    val imagePathList = Files.walk(targetDir).use { paths ->
        paths.filter { it != targetDir }
            .toList()
    }

    val pattern = Regex("""^(\d+)\.\w+$""")
    for (imagePath in imagePathList) {
        if (imagePath.isDirectory()) {
            // サブディレクトリを削除
            imagePath.toFile().deleteRecursively()
        } else {
            // ファイル名判定
            if (imagePath.fileName.toString().matches(pattern)) {
                val fileNumber = imagePath.fileName.toString().split(".")[0].toIntOrNull()
                if (fileNumber != null) {
                    if (fileNumber in 1..10000) {
                        // 1~10000.*のファイルはスキップ
                        continue
                    }
                }
            }
            // ファイルの削除
            Files.deleteIfExists(imagePath)
        }
    }
}
