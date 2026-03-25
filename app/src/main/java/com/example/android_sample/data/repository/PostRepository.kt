package com.example.android_sample.data.repository

import com.example.android_sample.data.model.Post
import com.example.android_sample.data.remote.PostApiService
import com.example.framework.network.ApiResult
import com.example.framework.network.NetworkManager
import com.example.framework.network.safeApiCall

/**
 * 示例 Repository
 *
 * 通过 [safeApiCall] 将网络请求包装为 [ApiResult]，ViewModel 只需处理结果，无需关心异常。
 */
class PostRepository {

    private val api: PostApiService by lazy {
        NetworkManager.createService(PostApiService::class.java)
    }

    suspend fun fetchPosts(): ApiResult<List<Post>> = safeApiCall {
        api.getPosts()
    }

    suspend fun fetchPost(id: Int): ApiResult<Post> = safeApiCall {
        api.getPost(id)
    }
}

