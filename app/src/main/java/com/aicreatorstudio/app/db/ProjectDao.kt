package com.aicreatorstudio.app.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ProjectDao {
    
    // Project operations
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProject(project: VideoProjectEntity)

    @Update
    suspend fun updateProject(project: VideoProjectEntity)

    @Delete
    suspend fun deleteProject(project: VideoProjectEntity)

    @Query("SELECT * FROM projects ORDER BY createdAtEpochMillis DESC")
    fun getAllProjects(): Flow<List<VideoProjectEntity>>

    @Query("SELECT * FROM projects WHERE id = :id")
    fun getProjectByIdFlow(id: String): Flow<VideoProjectEntity?>

    @Query("SELECT * FROM projects WHERE id = :id")
    suspend fun getProjectById(id: String): VideoProjectEntity?

    @Query("DELETE FROM projects WHERE id = :id")
    suspend fun deleteProjectById(id: String)

    // Clip operations
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertClips(clips: List<VideoClipEntity>)

    @Update
    suspend fun updateClips(clips: List<VideoClipEntity>)

    @Delete
    suspend fun deleteClips(clips: List<VideoClipEntity>)

    @Query("SELECT * FROM clips WHERE projectId = :projectId")
    fun getClipsByProjectIdFlow(projectId: String): Flow<List<VideoClipEntity>>

    @Query("SELECT * FROM clips WHERE projectId = :projectId")
    suspend fun getClipsByProjectId(projectId: String): List<VideoClipEntity>

    @Query("DELETE FROM clips WHERE projectId = :projectId")
    suspend fun deleteClipsByProjectId(projectId: String)

    @Query("DELETE FROM clips WHERE id = :clipId")
    suspend fun deleteClipById(clipId: String)

    // Transactional operations
    @Transaction
    suspend fun insertProjectWithClips(project: VideoProjectEntity, clips: List<VideoClipEntity>) {
        insertProject(project)
        if (clips.isNotEmpty()) insertClips(clips)
    }

    @Transaction
    suspend fun deleteProjectWithClips(projectId: String) {
        deleteClipsByProjectId(projectId)
        deleteProjectById(projectId)
    }

    @Transaction
    suspend fun replaceClipsForProject(projectId: String, clips: List<VideoClipEntity>) {
        deleteClipsByProjectId(projectId)
        if (clips.isNotEmpty()) insertClips(clips)
    }
}