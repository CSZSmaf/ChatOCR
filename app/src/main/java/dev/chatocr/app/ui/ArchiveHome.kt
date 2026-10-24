package dev.chatocr.app.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chatocr.app.*
import dev.chatocr.app.R

internal data class LibraryBook(val id: String, val title: String, val detail: String, val pending: Int,
    val recent: Boolean = false, val status: String = "")

@Composable
internal fun ArchiveHome(books: List<LibraryBook>, loading: Boolean, library: String,
    switchLibrary: (String) -> Unit, notice: String, open: (String) -> Unit, create: () -> Unit,
    createEnabled: Boolean, settings: () -> Unit, task: @Composable () -> Unit) {
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable(library) { mutableStateOf("") }
    val visible = remember(books, query) { books.filter { query.isBlank() || it.title.contains(query, true) || it.detail.contains(query, true) } }
    val articles = library == "articles"
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 12.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("拾语", color = Green, fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold,
                fontSize = 22.sp, lineHeight = 30.sp, modifier = Modifier.weight(1f))
            IconButton({ searching = !searching; if (!searching) query = "" }) {
                Icon(if (searching) Icons.Outlined.Close else Icons.Outlined.Search, if (searching) "关闭搜索" else "搜索收藏", tint = Muted)
            }
            IconButton(settings) { Icon(Icons.Outlined.Settings, "设置", tint = Muted) }
        }
        Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(if (articles) "我的文章" else "我的纪念册", style = MaterialTheme.typography.headlineMedium, color = Ink)
            ChoiceGroup(listOf("chats" to "聊天", "articles" to "文章"), library, switchLibrary, Modifier.fillMaxWidth())
            if (searching) TextField(query, { query = it }, placeholder = { Text("搜索名称") }, singleLine = true,
                leadingIcon = { Icon(Icons.Outlined.Search, null) }, shape = RoundedCornerShape(16.dp),
                colors = archiveFieldColors(), modifier = Modifier.fillMaxWidth())
        }
        task()
        if (notice.isNotBlank()) Text(notice, color = Green, style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 8.dp))
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 24.dp))
        val largeType = LocalDensity.current.fontScale > 1.3f
        LazyVerticalGrid(columns = GridCells.Adaptive(if (largeType) 210.dp else 150.dp),
            modifier = Modifier.weight(1f), contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
            if (!loading && visible.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.fillMaxWidth().padding(vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Outlined.AutoStories, null, Modifier.size(46.dp), tint = Green)
                    Spacer(Modifier.height(18.dp))
                    Text(if (query.isNotBlank()) "换个名称搜索" else if (articles) "导入第一篇文章" else "新建第一本纪念册", fontFamily = FontFamily.Serif, fontSize = 19.sp)
                }
            }
            items(visible, key = { it.id }) { book -> AlbumCover(book, book.recent, open) }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(top = 10.dp, bottom = 14.dp)) {
            Button(create, enabled = createEnabled, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                shape = RoundedCornerShape(14.dp), contentPadding = PaddingValues(16.dp)) {
                Icon(if (articles) Icons.Outlined.VideoLibrary else Icons.Outlined.Add, null, Modifier.size(24.dp))
                Spacer(Modifier.width(10.dp)); Text(if (articles) "导入录屏" else "新建纪念册", fontSize = 16.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
internal fun LibraryTaskCard(title: String, action: String, enabled: Boolean = true, progress: Float? = null, onAction: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(16.dp),
        modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 12.dp)) {
        Column(Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp, end = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer)
                TextButton(onAction, enabled = enabled) { Text(action) }
            }
            if (progress != null) LinearProgressIndicator(progress = { progress }, Modifier.fillMaxWidth().padding(end = 8.dp, bottom = 8.dp))
        }
    }
}

@Composable
private fun AlbumCover(album: LibraryBook, recent: Boolean, open: (String) -> Unit) {
    val colors = listOf(Color(0xFFC4CFBE), Color(0xFFEDE5D4), Color(0xFFC9D5D9), Color(0xFFD6B8A1))
    val tone = colors[Math.floorMod(album.id.hashCode(), colors.size)]
    Column(Modifier.fillMaxWidth().clickable { open(album.id) }.semantics(mergeDescendants = true) {
        contentDescription = "打开收藏：${album.title}，${album.detail}${if (album.pending > 0) "，${album.pending} 处待校对" else ""}${if (recent) "，上次阅读" else ""}"
    }) {
        Box(Modifier.fillMaxWidth().aspectRatio(.80f).clip(RoundedCornerShape(9.dp)).background(tone)) {
            Image(painterResource(R.drawable.book_cover), null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds, colorFilter = ColorFilter.tint(tone, BlendMode.Modulate))
            if (recent) Icon(Icons.Outlined.Bookmark, null, Modifier.align(Alignment.TopEnd).padding(12.dp).size(18.dp), tint = Color(0xFF315748))
            Column(Modifier.fillMaxSize().padding(start = 24.dp, end = 16.dp, top = 22.dp, bottom = 20.dp)) {
                HorizontalDivider(Modifier.width(22.dp), color = Color(0xFF57705E), thickness = .7.dp)
                Spacer(Modifier.weight(.65f))
                Text(album.title, color = Color(0xFF213D31), fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.SemiBold, fontSize = if (album.title.length > 18) 18.sp else 22.sp,
                    lineHeight = if (album.title.length > 18) 27.sp else 31.sp, maxLines = 4, overflow = TextOverflow.Ellipsis,
                    style = LocalTextStyle.current.copy(lineBreak = LineBreak.Heading))
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(Modifier.width(22.dp), color = Color(0xFF57705E), thickness = .7.dp)
                Spacer(Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(9.dp))
        @OptIn(ExperimentalLayoutApi::class)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(album.detail, color = Ink, fontSize = 14.sp, lineHeight = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (album.status.isNotBlank()) Text(album.status, color = Green, style = MaterialTheme.typography.bodySmall)
            if (album.pending > 0) Text("待校对 ${album.pending}", color = Amber, style = MaterialTheme.typography.bodySmall)
        }
    }
}
