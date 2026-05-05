package com.tourism.tourcatalog.service;

import com.cloudinary.Cloudinary;
import com.cloudinary.utils.ObjectUtils;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

/**
 * Uploads review images to Cloudinary.
 * Returns the secure Cloudinary URL (https://res.cloudinary.com/...) so anyone
 * can open the URL directly without any gateway or static-file config.
 */
@Service
public class FileStorageService {

    private final Cloudinary cloudinary;

    public FileStorageService(Cloudinary cloudinary) {
        this.cloudinary = cloudinary;
    }

    /**
     * Uploads a file to Cloudinary under the folder "review-images".
     *
     * @param file the uploaded file
     * @return Cloudinary secure URL, e.g. "https://res.cloudinary.com/dnt8vx1at/image/upload/v.../review-images/xxx.jpg"
     * @throws IOException if upload fails
     */
    @SuppressWarnings("unchecked")
    public String saveFile(MultipartFile file) throws IOException {
        Map<String, Object> result = cloudinary.uploader().upload(
                file.getBytes(),
                ObjectUtils.asMap(
                        "folder", "review-images",
                        "resource_type", "image"
                )
        );
        return (String) result.get("secure_url");
    }
}
