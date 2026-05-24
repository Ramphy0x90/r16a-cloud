package com.r16a.r16a_cloud.file;

import com.r16a.r16a_cloud.exception.StorageException;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
public class FileZipService {

    public StreamingResponseBody zipFiles(List<File> files) {
        return outputStream -> {
            try (ZipOutputStream zipOut = new ZipOutputStream(outputStream)) {
                Set<String> usedRootNames = new LinkedHashSet<>();
                for (File file : files) {
                    Path source = Path.of(file.getFsPath());
                    if (!Files.exists(source)) {
                        throw new StorageException("Source path not found for download: " + source);
                    }

                    String rootName = uniqueRootName(file.getName(), usedRootNames);
                    if (Files.isDirectory(source)) {
                        zipDirectory(zipOut, source, rootName);
                    } else {
                        zipRegularFile(zipOut, source, rootName);
                    }
                }

                zipOut.finish();
            } catch (IOException ex) {
                throw new StorageException("Failed to build zip for download.", ex);
            }
        };
    }

    private void zipDirectory(ZipOutputStream zipOut, Path directoryPath, String rootName) throws IOException {
        try (var stream = Files.walk(directoryPath)) {
            for (Path path : (Iterable<Path>) stream::iterator) {
                if (path.equals(directoryPath)) {
                    continue;
                }

                Path relativePath = directoryPath.relativize(path);
                String entryName = rootName + "/" + relativePath.toString().replace('\\', '/');

                if (Files.isDirectory(path)) {
                    zipOut.putNextEntry(new ZipEntry(entryName + "/"));
                    zipOut.closeEntry();
                } else {
                    zipRegularFile(zipOut, path, entryName);
                }
            }
        }
    }

    private void zipRegularFile(ZipOutputStream zipOut, Path path, String entryName) throws IOException {
        zipOut.putNextEntry(new ZipEntry(entryName));
        Files.copy(path, zipOut);
        zipOut.closeEntry();
    }

    private String uniqueRootName(String baseName, Set<String> usedRootNames) {
        String candidate = baseName;
        int index = 1;
        while (!usedRootNames.add(candidate)) {
            candidate = baseName + "_" + index++;
        }
        return candidate;
    }
}
