package org.example.model;

import lombok.Data;

@Data
public class ArticleInfo {
    private String title;
    private String slug;
    private String imageUrl;
    private String link;
    private String content;
    private String keyPhrase;
}
