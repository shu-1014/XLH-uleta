package com.iki.xlh.data.repository

import com.iki.xlh.data.model.Module
import com.iki.xlh.data.model.ModuleUpdateInfo

interface ModuleRepository {
    suspend fun getModules(): Result<List<Module>>
    suspend fun checkUpdate(module: Module): Result<ModuleUpdateInfo>
}
