package com.example.eggsactmatch

import android.content.SharedPreferences
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

val MinecraftFont = FontFamily(Font(R.font.minecraft_font))

// --- MODELS & NAVIGATION ---
sealed class Screen {
    object Login : Screen(); object Register : Screen(); object Home : Screen()
    object DifficultySelect : Screen(); object Game : Screen(); object Leaderboard : Screen()
}

enum class GameDifficulty(val label: String, val rows: Int, val cols: Int) {
    EASY("Easy (3x4)", 4, 3), MEDIUM("Medium (4x5)", 5, 4), HARD("Hard (6x6)", 6, 6)
}

data class MemoryCard(val id: Int, val frontImageRes: Int, var isFlipped: Boolean = false, var isMatched: Boolean = false)

// --- MAIN ACTIVITY ---
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var currentScreen by remember { mutableStateOf<Screen>(Screen.Login) }
            var currentUser by remember { mutableStateOf<String?>(null) }
            var gameKey by remember { mutableIntStateOf(0) }
            var selectedDifficulty by remember { mutableStateOf(GameDifficulty.EASY) }
            var sessionScore by remember { mutableIntStateOf(0) }
            val context = this@MainActivity
            val sharedPrefs = context.getSharedPreferences("EggsactPrefs", MODE_PRIVATE)
            val accounts = remember { mutableStateMapOf<String, String>().apply { sharedPrefs.getString("accounts", null)?.split("|")?.forEach { val parts = it.split(","); if (parts.size == 2) this[parts[0]] = parts[1] } } }
            val leaderboard = remember { mutableStateMapOf(GameDifficulty.EASY to mutableStateListOf<Pair<String, Int>>(), GameDifficulty.MEDIUM to mutableStateListOf<Pair<String, Int>>(), GameDifficulty.HARD to mutableStateListOf<Pair<String, Int>>()) }

            LaunchedEffect(Unit) { GameDifficulty.values().forEach { diff ->
                    val saved = loadLeaderboard(sharedPrefs, diff)
                    leaderboard[diff]?.addAll(saved) }      }

            Surface(modifier = Modifier.fillMaxSize()) {
                when (currentScreen) {
                    is Screen.Login -> LoginScreen(
                        accounts = accounts,
                        sharedPrefs = sharedPrefs,
                        onLoginSuccess = { username -> currentUser = username; currentScreen = Screen.Home },
                        onNavigateToRegister = { currentScreen = Screen.Register }
                    )
                    is Screen.Register -> RegisterScreen(
                        accounts = accounts,
                        sharedPrefs = sharedPrefs,
                        onRegisterSuccess = { currentScreen = Screen.Login },
                        onBackToLogin = { currentScreen = Screen.Login }
                    )
                    is Screen.Home -> HomeScreen(
                        currentUser = currentUser,
                        onPlay = { currentScreen = Screen.DifficultySelect },
                        onLeaderboard = { currentScreen = Screen.Leaderboard },
                        onExit = { currentUser = null; currentScreen = Screen.Login }
                    )
                    is Screen.DifficultySelect -> DifficultyScreen(
                        onSelect = { diff -> selectedDifficulty = diff; currentScreen = Screen.Game },
                        onBack = { currentScreen = Screen.Home }
                    )
                    is Screen.Game -> EggsactMatchGame(
                        difficulty = selectedDifficulty,
                        key = gameKey,
                        score = sessionScore,
                        onScoreChange = { sessionScore = it },
                        currentUser = currentUser,
                        onSaveScore = { if (sessionScore > 0) currentUser?.let { u -> leaderboard[selectedDifficulty]?.let { l -> val i = l.indexOfFirst { it.first == u }; if (i != -1) { if (sessionScore > l[i].second) l[i] = u to sessionScore } else l.add(u to sessionScore); saveLeaderboard(sharedPrefs, selectedDifficulty,l) } }; sessionScore = 0; currentScreen = Screen.Home },
                        onExitGame = { sessionScore = 0; currentScreen = Screen.Home },
                        onViewLeaderboard = { currentScreen = Screen.Leaderboard },
                        onContinue = { gameKey++ }
                    )
                    is Screen.Leaderboard -> LeaderboardScreen(
                        leaderboard.mapValues { it.value.toList() }) { currentScreen = Screen.Home
                    }
                }
            }
        }
    }
}

// --- GAME ENGINE ---
@Composable
fun EggsactMatchGame(difficulty: GameDifficulty, key: Int, score: Int, onScoreChange: (Int) -> Unit, currentUser: String?, onSaveScore: () -> Unit, onExitGame: () -> Unit, onViewLeaderboard: () -> Unit, onContinue: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var timeLeft by remember { mutableIntStateOf(180) }
    var comboMultiplier by remember { mutableIntStateOf(1) }
    var consecutiveMatches by remember { mutableIntStateOf(0) }
    var isPaused by remember { mutableStateOf(false) }
    var showWinnerPrompt by remember { mutableStateOf(false) }
    var showTimerOutPrompt by remember { mutableStateOf(false) }
    var showPenalty by remember { mutableStateOf(false) }
    var showComboAnimation by remember { mutableStateOf(false) }

    val backgroundResources = remember {
        (1..7).map { i -> context.resources.getIdentifier("bg_$i", "drawable", context.packageName) }.filter { it != 0 }
    }
    val allEggResources = remember {
        (1..92).map { i -> context.resources.getIdentifier("egg_$i", "drawable", context.packageName) }.filter { it != 0 }
    }
    val cards = remember(difficulty, key) {
        val pairsNeeded = (difficulty.rows * difficulty.cols) / 2
        val selected = allEggResources.shuffled().take(pairsNeeded)
        (selected + selected).shuffled().mapIndexed { i, res -> MemoryCard(i, res) }.toMutableStateList()
    }
    var flippedIndices by remember { mutableStateOf(listOf<Int>()) }
    var currentBg by remember { mutableStateOf(backgroundResources.random()) }

    LaunchedEffect(difficulty) { currentBg = backgroundResources.random() }
    LaunchedEffect(key1 = timeLeft, key2 = isPaused) {
        if (timeLeft > 0 && !isPaused && !showWinnerPrompt) { delay(1000L); timeLeft-- }
        else if (timeLeft <= 0 && !showWinnerPrompt) showTimerOutPrompt = true
    }

    LaunchedEffect(showPenalty) { if (showPenalty) { delay(1000); showPenalty = false } }
    LaunchedEffect(showComboAnimation) { if (showComboAnimation) { delay(1000); showComboAnimation = false } }

    Box(modifier = Modifier.fillMaxSize()) {
        Image(painter = painterResource(id = currentBg), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)

        Column(modifier = Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Image(painter = painterResource(id = R.drawable.eggsact_logo), contentDescription = "Eggsact Logo", modifier = Modifier.height(70.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("MATCH", fontSize = 42.sp, color = Color.White, fontFamily = MinecraftFont, style = TextStyle(shadow = Shadow(color = Color.Black, offset = Offset(3f, 3f), blurRadius = 4f))) }

            Spacer(modifier = Modifier.height(16.dp))

            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("TIME: $timeLeft", color = if(timeLeft < 10) Color.Red else Color.White, fontFamily = MinecraftFont, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    if (showPenalty) Text(" -3s", color = Color.Red, fontFamily = MinecraftFont, fontWeight = FontWeight.Black, modifier = Modifier.padding(start = 4.dp))
                }
                Text("SCORE: $score", color = Color.White, fontFamily = MinecraftFont, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }

            if (showComboAnimation) Text("COMBO: x$comboMultiplier", color = Color.Yellow, fontFamily = MinecraftFont, fontWeight = FontWeight.Black, fontSize = 22.sp)
            else Spacer(modifier = Modifier.height(30.dp))

            Spacer(modifier = Modifier.weight(1f))

            LazyVerticalGrid(
                columns = GridCells.Fixed(difficulty.cols),
                modifier = Modifier.wrapContentHeight().fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(cards) { index, card ->
                    CardView(card) {
                        if (flippedIndices.size < 2 && !card.isFlipped && !card.isMatched && !isPaused) {
                            cards[index] = cards[index].copy(isFlipped = true)
                            flippedIndices = flippedIndices + index
                            if (flippedIndices.size == 2) {
                                scope.launch {
                                    delay(600)
                                    val idx1 = flippedIndices[0]; val idx2 = flippedIndices[1]
                                    if (cards[idx1].frontImageRes == cards[idx2].frontImageRes) {
                                        consecutiveMatches++
                                        if (consecutiveMatches > 1) { comboMultiplier = 1 shl (consecutiveMatches - 1); showComboAnimation = true }
                                        onScoreChange(score + (10 * comboMultiplier))
                                        cards[idx1] = cards[idx1].copy(isMatched = true); cards[idx2] = cards[idx2].copy(isMatched = true)
                                        if (cards.all { it.isMatched }) showWinnerPrompt = true
                                    } else {
                                        cards[idx1] = cards[idx1].copy(isFlipped = false); cards[idx2] = cards[idx2].copy(isFlipped = false)
                                        comboMultiplier = 1; consecutiveMatches = 0; timeLeft -= 3; showPenalty = true
                                    }
                                    flippedIndices = emptyList()
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            Button(onClick = { isPaused = true }, modifier = Modifier.padding(bottom = 16.dp).width(150.dp).height(50.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0x88000000)), shape = RoundedCornerShape(12.dp)) {
                Text("PAUSE", fontFamily = MinecraftFont, fontWeight = FontWeight.Bold, color = Color.White)
            }
        }

        if (isPaused) GameDialog("PAUSED", "RESUME", { isPaused = false }, "RESTART", { isPaused = false; timeLeft = 180; onScoreChange(0); cards.indices.forEach { i -> cards[i] = cards[i].copy(isFlipped = false, isMatched = false) }; scope.launch { delay(400); onContinue() } }, "SAVE & EXIT", { onSaveScore() })
        if (showWinnerPrompt) GameDialog("WINNER!", "CONTINUE", { showWinnerPrompt = false; cards.indices.forEach { i -> cards[i] = cards[i].copy(isFlipped = false, isMatched = false) }; scope.launch { delay(400); onContinue() } }, "SAVE SCORE", { onSaveScore() }, "EXIT", { onExitGame() }, score = score)
        if (showTimerOutPrompt) GameDialog("TIME OUT!", "SAVE SCORE", { onSaveScore() }, "EXIT", { onExitGame() }, score = score)
    }
}

// --- AUTH SCREENS ---
@Composable
fun LoginScreen(accounts: Map<String, String>, sharedPrefs: SharedPreferences, onLoginSuccess: (String) -> Unit, onNavigateToRegister: () -> Unit) {
    val context = LocalContext.current
    var user by remember { mutableStateOf("") }; var pass by remember { mutableStateOf("") }
    Box(modifier = Modifier.fillMaxSize()) {
        Image(painter = painterResource(id = R.drawable.menu_bg), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)))
        Column(modifier = Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text("LOG IN", fontSize = 46.sp, color = Color.White, fontFamily = MinecraftFont, fontWeight = FontWeight.Black)
            Spacer(modifier = Modifier.height(32.dp))
            TextField(value = user, onValueChange = { user = it }, label = { Text("User") }, modifier = Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(8.dp))
            TextField(value = pass, onValueChange = { pass = it }, label = { Text("Pass") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(32.dp))
            MenuButton("LOG IN") {
                if (accounts[user] == pass) { onLoginSuccess(user) }
                else Toast.makeText(context, "Invalid Credentials!", Toast.LENGTH_SHORT).show()
            }
            TextButton(onClick = onNavigateToRegister) { Text("No account? Register", color = Color.White, fontFamily = MinecraftFont) }
        }
    }
}

@Composable
fun RegisterScreen(accounts: MutableMap<String, String>, sharedPrefs: SharedPreferences, onRegisterSuccess: () -> Unit, onBackToLogin: () -> Unit) {
    val context = LocalContext.current
    var user by remember { mutableStateOf("") }; var pass by remember { mutableStateOf("") }
    Box(modifier = Modifier.fillMaxSize()) {
        Image(painter = painterResource(id = R.drawable.menu_bg), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)))
        Column(modifier = Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text("REGISTER", fontSize = 46.sp, color = Color.White, fontFamily = MinecraftFont, fontWeight = FontWeight.Black)
            Spacer(modifier = Modifier.height(32.dp))
            TextField(value = user, onValueChange = { user = it }, label = { Text("New Username") }, modifier = Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(8.dp))
            TextField(value = pass, onValueChange = { pass = it }, label = { Text("New Password") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(32.dp))
            MenuButton("CREATE ACCOUNT") {
                if (user.isNotEmpty() && pass.isNotEmpty()) {
                    if (accounts.containsKey(user)) {
                        Toast.makeText(context, "Username already exists!", Toast.LENGTH_SHORT).show()
                    } else {
                        accounts[user] = pass; sharedPrefs.edit().putString("accounts", accounts.entries.joinToString("|") { "${it.key},${it.value}" }).apply()
                        Toast.makeText(context, "Account Created!", Toast.LENGTH_SHORT).show()
                        onRegisterSuccess()
                    }
                } else Toast.makeText(context, "Fill all fields", Toast.LENGTH_SHORT).show()
            }
            TextButton(onClick = onBackToLogin) { Text("Back to Login", color = Color.White, fontFamily = MinecraftFont) }
        }
    }
}

// --- SHARED UI ---
@Composable
fun CardView(card: MemoryCard, onClick: () -> Unit) {
    val isFront = card.isFlipped || card.isMatched
    val rotation by animateFloatAsState(targetValue = if (isFront) 180f else 0f, animationSpec = tween(durationMillis = 450), label = "")
    val tintAlpha by animateFloatAsState(targetValue = if (card.isMatched) 0.5f else 0f, animationSpec = tween(durationMillis = 400), label = "")
    val scale by animateFloatAsState(targetValue = if (card.isMatched) 0.9f else 1f, label = "")
    val density = LocalDensity.current.density
    val isFrontVisible = rotation > 90f
    Box(modifier = Modifier.aspectRatio(1f).graphicsLayer(rotationY = rotation, cameraDistance = 12 * density, scaleX = scale, scaleY = scale).clip(RoundedCornerShape(12.dp)).clickable(indication = null, interactionSource = remember { MutableInteractionSource() } ) { onClick() }.border(BorderStroke(2.dp, Color(0xFF5D4037)), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
        val img = if (isFrontVisible) card.frontImageRes else R.drawable.card_cover
        if (isFrontVisible) { Box(modifier = Modifier.matchParentSize().background(Color.White)) }
        Image(painter = painterResource(id = img), contentDescription = null, modifier = Modifier.fillMaxSize().graphicsLayer(scaleX = if (rotation > 90f) -1f else 1f).padding(if (isFrontVisible) 8.dp else 0.dp), contentScale = ContentScale.Fit)
        if (card.isMatched) { Box(modifier = Modifier.matchParentSize().background(Color.Black.copy(alpha = tintAlpha))) }
    }
}

@Composable
fun MenuButton(text: String, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = Modifier.padding(vertical = 4.dp).width(240.dp).height(55.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)), shape = RoundedCornerShape(12.dp)) {
        Text(text.uppercase(), fontFamily = MinecraftFont, fontWeight = FontWeight.Bold, color = Color.White, fontSize = 18.sp)
    }
}

@Composable
fun GameDialog(title: String, pBtn: String, onP: () -> Unit, sBtn: String, onS: () -> Unit, eBtn: String? = null, onE: (() -> Unit)? = null, score: Int? = null) {
    Dialog(onDismissRequest = {}) {
        Box(modifier = Modifier.clip(RoundedCornerShape(24.dp)).background(Color(0xFFE8F5E9)).padding(24.dp)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(title, fontSize = 30.sp, fontFamily = MinecraftFont, fontWeight = FontWeight.ExtraBold, color = Color(0xFF2E7D32))
                if (score != null) Text("Score: $score", fontSize = 22.sp, fontFamily = MinecraftFont, color = Color.Black)
                Spacer(modifier = Modifier.height(24.dp))
                MenuButton(pBtn, onP); MenuButton(sBtn, onS)
                if (eBtn != null && onE != null) MenuButton(eBtn, onE)
            }
        }
    }
}

@Composable
fun HomeScreen(currentUser: String?, onPlay: () -> Unit, onLeaderboard: () -> Unit, onExit: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize()) {
        Image(painter = painterResource(id = R.drawable.menu_bg), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)))
        Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Image(painter = painterResource(id = R.drawable.eggsact_logo), contentDescription = "Eggsact Logo", modifier = Modifier.height(70.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("MATCH", fontSize = 42.sp, color = Color.White, fontFamily = MinecraftFont) }
            Spacer(modifier = Modifier.height(40.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = "Welcome, ", color = Color.White, fontFamily = MinecraftFont, fontSize = 18.sp)
                Text(text = currentUser ?: "", color = Color(0xFFFFD700), fontFamily = MinecraftFont, fontSize = 20.sp, fontWeight = FontWeight.Bold) }
            Spacer(modifier = Modifier.height(30.dp))
            MenuButton("PLAY", onPlay); MenuButton("LEADERBOARD", onLeaderboard); MenuButton("EXIT", onExit)
        }
    }
}

@Composable
fun DifficultyScreen(onSelect: (GameDifficulty) -> Unit, onBack: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize()) {
        Image(painter = painterResource(id = R.drawable.difficulty_bg), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)))
        Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text("CHOOSE DIFFICULTY", color = Color.White, fontSize = 26.sp, fontFamily = MinecraftFont, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(32.dp))
            GameDifficulty.values().forEach { diff -> MenuButton(diff.label) { onSelect(diff) } }
            Spacer(modifier = Modifier.height(40.dp))
            MenuButton("BACK", onBack)
        }
    }
}

@Composable
fun LeaderboardScreen(leaderboard: Map<GameDifficulty, List<Pair<String, Int>>>, onBack: () -> Unit) {
    var viewDiff by remember { mutableStateOf(GameDifficulty.EASY) }
    Box(modifier = Modifier.fillMaxSize().background(Color(0xFFF1F8E9))) {
        Column(modifier = Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("LEADERBOARD", color = Color(0xFF1B5E20), fontSize = 32.sp, fontFamily = MinecraftFont, fontWeight = FontWeight.Black)
            Spacer(modifier = Modifier.height(16.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                GameDifficulty.values().forEach { d ->
                    Button(onClick = { viewDiff = d }, modifier = Modifier.weight(1f).padding(2.dp), colors = ButtonDefaults.buttonColors(containerColor = if(viewDiff == d) Color(0xFF2E7D32) else Color.LightGray))
                    { Text(d.label.take(4), fontSize = 10.sp, fontFamily = MinecraftFont) }
                }
            }
            Spacer(modifier = Modifier.weight(1f))
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("PLAYER", fontSize = 20.sp, fontFamily = MinecraftFont, fontWeight = FontWeight.Bold)
                Text("SCORE", fontSize = 20.sp, fontFamily = MinecraftFont, fontWeight = FontWeight.Bold) }
            val scores = leaderboard[viewDiff]?.sortedByDescending { it.second } ?: emptyList()
            Column(modifier = Modifier.fillMaxWidth()) { scores.forEach { (name, score) ->
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(text = name, fontFamily = MinecraftFont, fontSize = 18.sp, color = Color.Black)
                        Text(text = score.toString(), fontFamily = MinecraftFont, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFF2E7D32)) }
                }
            }
            Spacer(modifier = Modifier.weight(1f))
            MenuButton("BACK", onBack)
        }
    }
}

fun saveLeaderboard(sharedPrefs: SharedPreferences, difficulty: GameDifficulty, leaderboard: List<Pair<String, Int>>) {
    val key = "leaderboard_${difficulty.name}"
    val data = leaderboard.joinToString("|") { "${it.first},${it.second}" }
    sharedPrefs.edit().putString(key, data).apply()
}

fun loadLeaderboard(sharedPrefs: SharedPreferences, difficulty: GameDifficulty): List<Pair<String, Int>> {
    val key = "leaderboard_${difficulty.name}"
    return sharedPrefs.getString(key, null)
        ?.split("|")
        ?.mapNotNull {
            val parts = it.split(",")
            if (parts.size == 2) parts[0] to parts[1].toIntOrNull()
            else null
        }
        ?.map { it.first to it.second!! }
        ?: emptyList()
}