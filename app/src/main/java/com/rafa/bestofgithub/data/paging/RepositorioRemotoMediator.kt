package com.rafa.bestofgithub.data.paging

import androidx.paging.ExperimentalPagingApi
import androidx.paging.LoadType
import androidx.paging.LoadType.*
import androidx.paging.PagingState
import androidx.paging.RemoteMediator
import androidx.room.withTransaction
import com.rafa.bestofgithub.commons.Constants
import com.rafa.bestofgithub.commons.Resposta
import com.rafa.bestofgithub.data.db.GitRepositoryDataBase
import com.rafa.bestofgithub.data.remote.dto.toItems
import com.rafa.bestofgithub.data.remote.service.GithubApi
import com.rafa.bestofgithub.domain.model.Items
import com.rafa.bestofgithub.domain.model.RemoteKey
import com.rafa.bestofgithub.domain.model.ResultResponse
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException
import javax.inject.Inject


private const val PAGE_START = 1


@OptIn(ExperimentalPagingApi::class)
class RepositorioRemotoMediator @Inject constructor(
    private val githubApi: GithubApi,
    private val githubDB: GitRepositoryDataBase
) : RemoteMediator<Int, Items>() {

    override suspend fun initialize(): InitializeAction {
        return InitializeAction.LAUNCH_INITIAL_REFRESH
    }

    @Throws
    override suspend fun load(loadType: LoadType, state: PagingState<Int, Items>): MediatorResult {
        val page = when (val pageKeyData = getKeyPageData(loadType, state)) {
            is MediatorResult.Success -> {
                return pageKeyData
            }
            else -> {
                pageKeyData as Int
            }
        }
        try {
            val response = returnResponse(page, state)

            if (!response.isSuccessful)
                return MediatorResult.Error(Throwable(message = "500"))
            val result = configReturnResponse(response, page)

            transactionBd(loadType, result.remoteKeys, result.items)

            return MediatorResult.Success(endOfPaginationReached = result.items.isEmpty())
        } catch (exception: IOException) {
            return MediatorResult.Error(exception)
        } catch (exception: HttpException) {
            return MediatorResult.Error(exception)
        }
    }

    private suspend fun getKeyPageData(loadType: LoadType, state: PagingState<Int, Items>): Any {
        return when (loadType) {
            REFRESH -> {
                val remoteKeys = getRemoteKeyClosesToCurrentePosition(state)
                remoteKeys?.nextKey?.minus(1) ?: PAGE_START
            }
            APPEND -> {
                val remoteKeys = getLastRemoteKey(state)
                val nextkey = remoteKeys?.nextKey
                return nextkey ?: MediatorResult.Success(endOfPaginationReached = false)
            }
            PREPEND -> {
                val remoteKeys = getFirstRemoteKey(state)
                val prevKey = remoteKeys?.prevKey ?: return MediatorResult.Success(
                    endOfPaginationReached = true
                )
                prevKey
            }
        }
    }

    private suspend fun getRemoteKeyClosesToCurrentePosition(state: PagingState<Int, Items>): RemoteKey? {
        return state.anchorPosition?.let { position ->
            state.closestItemToPosition(position)?.id?.let { repoId ->
                githubDB.remoteKeyDao().pegaKeyById(repoId.toString())
            }
        }
    }

    private suspend fun getLastRemoteKey(state: PagingState<Int, Items>): RemoteKey? {
        return state.pages
            .lastOrNull { it.data.isNotEmpty() }
            ?.data?.lastOrNull()
            ?.let { item -> githubDB.remoteKeyDao().pegaKeyById(item.id.toString()) }
    }


    private suspend fun getFirstRemoteKey(state: PagingState<Int, Items>): RemoteKey? {
        return state.pages
            .firstOrNull { it.data.isNotEmpty() }
            ?.data?.firstOrNull()
            ?.let { item -> githubDB.remoteKeyDao().pegaKeyById(item.id.toString()) }
    }

    private suspend fun transactionBd(
        loadType: LoadType,
        keys: List<RemoteKey>,
        items: List<Items>
    ) {
        githubDB.withTransaction {
            if (loadType == REFRESH) {
                githubDB.gitRepositoryDao().deletaItems()
                githubDB.remoteKeyDao().deletaKeys()
            }
            githubDB.remoteKeyDao().adicionaKeys(keys)
            githubDB.gitRepositoryDao().adicionaItems(items)
        }
    }

    private suspend fun returnResponse(
        page: Int,
        state: PagingState<Int, Items>
    ): Response<Resposta> {
        return githubApi.getRepositorys(
            q = Constants.SEARCH_KEYWORD,
            order = Constants.ORDER_DESC,
            sort = Constants.SORT_STARS,
            perPage = state.config.pageSize,
            page = page
        )
    }

    private fun configReturnResponse(response: Response<Resposta>, page: Int): ResultResponse {
        val isEndOfList = response.body()?.items?.isEmpty()

        val prevKey = if (page == PAGE_START) null else page - 1
        val nextKey = if (isEndOfList != false) null else page + 1

        val keys = response.body()?.items?.map {
            RemoteKey(id = it.id.toString(), prevKey = prevKey, nextKey = nextKey)
        }
        val items = response.body()?.items?.map { it.toItems() }

        return ResultResponse(
            remoteKeys = keys ?: emptyList(),
            items = items ?: emptyList()
        )
    }
}