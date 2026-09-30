#!/bin/bash
# Move UI elements from gifboard to main

GIF_SRC="daddyboard-gif/app/src/main/java/com/gifboard"
MAIN_DST="daddyboard-main/app/src/main/java/helium314/keyboard/latin/gif"

mkdir -p "$MAIN_DST"
cp $GIF_SRC/GifAdapter.kt "$MAIN_DST/"
cp $GIF_SRC/GifHistoryAdapter.kt "$MAIN_DST/"
cp $GIF_SRC/GifImageLoader.kt "$MAIN_DST/"
cp $GIF_SRC/GifItem.kt "$MAIN_DST/"
cp $GIF_SRC/SearchHistoryAdapter.kt "$MAIN_DST/"
cp $GIF_SRC/SearchHistoryDbHelper.kt "$MAIN_DST/"
cp $GIF_SRC/AspectRatioDraweeView.kt "$MAIN_DST/"

# Copy layouts
cp daddyboard-gif/app/src/main/res/layout/gif*.xml daddyboard-main/app/src/main/res/layout/
cp daddyboard-gif/app/src/main/res/layout/item*.xml daddyboard-main/app/src/main/res/layout/
cp daddyboard-gif/app/src/main/res/layout/search_history_item.xml daddyboard-main/app/src/main/res/layout/

# Delete from gifboard
rm -rf daddyboard-gif/app/src/main/res/layout/*
rm $GIF_SRC/GifAdapter.kt $GIF_SRC/GifHistoryAdapter.kt $GIF_SRC/GifImageLoader.kt $GIF_SRC/SearchHistoryAdapter.kt $GIF_SRC/SearchHistoryDbHelper.kt $GIF_SRC/AspectRatioDraweeView.kt
rm $GIF_SRC/MainActivity.kt $GIF_RichEditText.kt $GIF_SRC/SettingsActivity.kt $GIF_SRC/TutorialActivity.kt $GIF_SRC/Tutorial*.kt $GIF_SRC/AdvancedSettingsFragment.kt $GIF_SRC/SettingsFragment.kt $GIF_SRC/GifBoardService.kt
