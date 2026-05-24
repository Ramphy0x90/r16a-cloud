package com.r16a.r16a_cloud.file.support;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
public class MediaDateExtractor {

    public Instant extractOldestDate(Path path) {
        try {
            com.drew.metadata.Metadata metadata =
                    com.drew.imaging.ImageMetadataReader.readMetadata(path.toFile());

            List<Instant> candidates = new ArrayList<>();

            // EXIF — most reliable for cameras
            com.drew.metadata.exif.ExifSubIFDDirectory subIFD =
                    metadata.getFirstDirectoryOfType(com.drew.metadata.exif.ExifSubIFDDirectory.class);
            if (subIFD != null) {
                addDate(candidates, subIFD.getDate(com.drew.metadata.exif.ExifSubIFDDirectory.TAG_DATETIME_ORIGINAL));
                addDate(candidates, subIFD.getDate(com.drew.metadata.exif.ExifSubIFDDirectory.TAG_DATETIME_DIGITIZED));
            }
            com.drew.metadata.exif.ExifIFD0Directory ifd0 =
                    metadata.getFirstDirectoryOfType(com.drew.metadata.exif.ExifIFD0Directory.class);
            if (ifd0 != null) {
                addDate(candidates, ifd0.getDate(com.drew.metadata.exif.ExifIFD0Directory.TAG_DATETIME));
            }

            // IPTC — used by some cameras and editing software
            com.drew.metadata.iptc.IptcDirectory iptc =
                    metadata.getFirstDirectoryOfType(com.drew.metadata.iptc.IptcDirectory.class);
            if (iptc != null) {
                addDate(candidates, iptc.getDate(com.drew.metadata.iptc.IptcDirectory.TAG_DATE_CREATED));
                addDate(candidates, iptc.getDate(com.drew.metadata.iptc.IptcDirectory.TAG_DIGITAL_DATE_CREATED));
            }

            // QuickTime / MOV / MP4
            com.drew.metadata.mov.QuickTimeDirectory qt =
                    metadata.getFirstDirectoryOfType(com.drew.metadata.mov.QuickTimeDirectory.class);
            if (qt != null) {
                addDate(candidates, qt.getDate(com.drew.metadata.mov.QuickTimeDirectory.TAG_CREATION_TIME));
            }
            com.drew.metadata.mov.metadata.QuickTimeMetadataDirectory qtMeta =
                    metadata.getFirstDirectoryOfType(com.drew.metadata.mov.metadata.QuickTimeMetadataDirectory.class);
            if (qtMeta != null) {
                addDate(candidates, qtMeta.getDate(com.drew.metadata.mov.metadata.QuickTimeMetadataDirectory.TAG_CREATION_DATE));
            }

            // XMP — last resort; skips metadata-write and profile dates on purpose
            com.drew.metadata.xmp.XmpDirectory xmp =
                    metadata.getFirstDirectoryOfType(com.drew.metadata.xmp.XmpDirectory.class);
            if (xmp != null && xmp.getXmpProperties() != null) {
                for (java.util.Map.Entry<String, String> entry : xmp.getXmpProperties().entrySet()) {
                    String propPath = entry.getKey();
                    if (propPath.equals("exif:DateTimeOriginal")
                            || propPath.equals("exif:DateTimeDigitized")
                            || propPath.equals("xmp:CreateDate")
                            || propPath.equals("photoshop:DateCreated")) {
                        parseXmpDate(entry.getValue()).ifPresent(candidates::add);
                    }
                }
            }

            Instant now = Instant.now();
            return candidates.stream()
                    .filter(i -> !i.isAfter(now))
                    .min(Instant::compareTo)
                    .orElse(null);

        } catch (Exception ex) {
            log.debug("No metadata date in {}: {}", path.getFileName(), ex.getMessage());
        }
        return null;
    }

    private void addDate(List<Instant> candidates, java.util.Date date) {
        if (date != null) candidates.add(date.toInstant());
    }

    private java.util.Optional<Instant> parseXmpDate(String raw) {
        try {
            String s = raw.trim();
            if (s.length() == 10) s += "T00:00:00Z";
            else if (!s.endsWith("Z") && !s.contains("+") && s.lastIndexOf('-') <= 7) s += "Z";
            return java.util.Optional.of(java.time.OffsetDateTime.parse(s).toInstant());
        } catch (Exception e) {
            return java.util.Optional.empty();
        }
    }
}
