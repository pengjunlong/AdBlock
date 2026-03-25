package com.pengjunlong.adblock.data.remote

import com.pengjunlong.adblock.data.model.Post
import retrofit2.http.GET
import retrofit2.http.Path

/**
 * 示例 API（使用 https://jsonplaceholder.typicode.com）
 */
interface PostApiService {

    @GET("posts")
    suspend fun getPosts(): List<Post>

    @GET("posts/{id}")
    suspend fun getPost(@Path("id") id: Int): Post
}

