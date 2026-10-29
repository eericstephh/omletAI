package com.aicreatorstudio.app.db

import androidx.room.TypeConverter
import com.aicreatorstudio.app.model.ClipAnimation
import com.aicreatorstudio.app.model.CropPreset

class Converters {
    
    @TypeConverter
    fun fromClipAnimation(animation: ClipAnimation): String = animation.name

    @TypeConverter
    fun toClipAnimation(value: String): ClipAnimation = ClipAnimation.valueOf(value)

    @TypeConverter
    fun fromCropPreset(preset: CropPreset): String = preset.name

    @TypeConverter
    fun toCropPreset(value: String): CropPreset = CropPreset.valueOf(value)
}