package com.vrogil.chat;

/** A chat line that has passed sanitisation and is ready to render. */
public record DisplayLine(String author, String body, int color) {
}
