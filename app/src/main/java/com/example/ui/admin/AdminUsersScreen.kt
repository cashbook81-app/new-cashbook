package com.example.ui.admin

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.remote.model.PENDING_STAFF_ID
import com.example.data.remote.model.StaffRole
import com.example.data.remote.model.StaffUserRecord
import com.example.data.remote.model.UNASSIGNED_BRANCH
import com.example.data.remote.model.resolvedRole
import com.example.data.repository.UserRepository
import com.example.ui.theme.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminUsersScreen(
    userRepository: UserRepository,
    currentAdminUid: String,
    knownBranches: List<String> = emptyList()
) {
    val usersResult by remember(userRepository) {
        userRepository.observeAllUsers()
    }.collectAsState(initial = Result.success(emptyList()))

    AdminUsersContent(
        users = usersResult.getOrDefault(emptyList()),
        loadError = usersResult.exceptionOrNull()?.localizedMessage,
        currentAdminUid = currentAdminUid,
        knownBranches = knownBranches,
        onSaveUserAccess = { targetUid, active, branchId, role, staffId ->
            userRepository.adminUpdateUserAccess(
                targetUid = targetUid,
                active = active,
                branchId = branchId,
                role = role,
                staffId = staffId
            )
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminUsersContent(
    users: List<StaffUserRecord>,
    loadError: String? = null,
    currentAdminUid: String,
    knownBranches: List<String> = emptyList(),
    onSaveUserAccess: suspend (
        targetUid: String,
        active: Boolean,
        branchId: String,
        role: StaffRole,
        staffId: String
    ) -> Result<Unit>
) {
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var searchQuery by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf("ALL") } // ALL, PENDING, CASHIER, AUDITOR, MANAGER
    var editingRecord by remember { mutableStateOf<StaffUserRecord?>(null) }

    val pendingCount = remember(users) { users.count { !it.user.active } }
    val activeCount = remember(users) { users.count { it.user.active } }

    val branchSuggestions = remember(users, knownBranches) {
        (listOf("SODO DACHI", "HEAD OFFICE", "ALL BRANCHES") +
            knownBranches +
            users.map { it.user.branchId })
            .map { it.trim().uppercase() }
            .filter { it.isNotBlank() && it != UNASSIGNED_BRANCH }
            .distinct()
            .sorted()
    }

    val filteredUsers = remember(users, searchQuery, selectedFilter) {
        users.filter { record ->
            val role = record.user.resolvedRole()
            val matchesFilter = when (selectedFilter) {
                "PENDING" -> !record.user.active
                "CASHIER" -> role == StaffRole.CASHIER
                "AUDITOR" -> role == StaffRole.AUDITOR
                "MANAGER" -> role == StaffRole.MANAGER
                "HEAD_OFFICE" -> role == StaffRole.HEAD_OFFICE
                else -> true
            }
            val q = searchQuery.trim().lowercase()
            val matchesQuery = q.isEmpty() ||
                record.user.fullName.lowercase().contains(q) ||
                record.user.email.lowercase().contains(q) ||
                record.user.branchId.lowercase().contains(q) ||
                record.user.staffId.lowercase().contains(q)
            matchesFilter && matchesQuery
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = IceSurface,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            Column(modifier = Modifier.background(White)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Staff Access Admin",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontFamily = PlusJakartaSansFontFamily,
                            fontSize = 17.sp
                        ),
                        fontWeight = FontWeight.Bold,
                        color = VaultSlateDeep
                    )
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = CyanSoftTint,
                        border = BorderStroke(1.dp, CyanBorder)
                    ) {
                        Text(
                            text = "ADMIN",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 10.sp,
                                letterSpacing = 0.6.sp
                            ),
                            fontWeight = FontWeight.Bold,
                            color = CyanDeep,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
                HorizontalDivider(thickness = 1.dp, color = SubtleOutline)
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(IceSurface)
                .padding(padding),
            contentAlignment = Alignment.TopCenter
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 600.dp)
                    .padding(horizontal = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(top = 12.dp, bottom = 20.dp)
            ) {
                // Unified Compact Banking KPI Strip
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = White,
                        border = BorderStroke(1.dp, SubtleOutline)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(IntrinsicSize.Min),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AdminKpiCell(
                                label = "TOTAL",
                                value = users.size.toString(),
                                accent = VaultSlateDeep,
                                modifier = Modifier.weight(1f)
                            )
                            VerticalDivider(
                                thickness = 1.dp,
                                color = SubtleOutline,
                                modifier = Modifier.padding(vertical = 10.dp)
                            )
                            AdminKpiCell(
                                label = "PENDING",
                                value = pendingCount.toString(),
                                accent = if (pendingCount > 0) AmberAccent else MutedNavyText,
                                modifier = Modifier.weight(1f)
                            )
                            VerticalDivider(
                                thickness = 1.dp,
                                color = SubtleOutline,
                                modifier = Modifier.padding(vertical = 10.dp)
                            )
                            AdminKpiCell(
                                label = "ACTIVE",
                                value = activeCount.toString(),
                                accent = SuccessGreen,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                // Compact Search & Filter Bar
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = {
                                Text(
                                    "Search staff, branch, ID",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MutedNavyText
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Rounded.Search,
                                    contentDescription = "Search staff",
                                    tint = MutedNavyText,
                                    modifier = Modifier.size(18.dp)
                                )
                            },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.5.sp),
                            shape = RoundedCornerShape(10.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = White,
                                unfocusedContainerColor = White,
                                focusedBorderColor = CyanPrimary,
                                unfocusedBorderColor = SubtleOutline
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("admin_search_input")
                        )

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            val filters = listOf(
                                "ALL" to "All (${users.size})",
                                "PENDING" to "Pending ($pendingCount)",
                                "CASHIER" to "Cashier",
                                "AUDITOR" to "Auditor",
                                "MANAGER" to "Manager",
                                "HEAD_OFFICE" to "Head Office"
                            )
                            filters.forEach { (key, label) ->
                                val selected = selectedFilter == key
                                Surface(
                                    onClick = { selectedFilter = key },
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (selected) VaultSlateDeep else White,
                                    border = BorderStroke(1.dp, if (selected) VaultSlateDeep else SubtleOutline),
                                    modifier = Modifier
                                        .heightIn(min = 34.dp)
                                        .testTag("admin_filter_$key")
                                ) {
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                                    ) {
                                        Text(
                                            text = label,
                                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.5.sp),
                                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                            color = if (selected) White else NavyText,
                                            maxLines = 1
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                if (loadError != null) {
                    item {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = DangerSoftTint,
                            border = BorderStroke(1.dp, DangerBorder),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = loadError,
                                style = MaterialTheme.typography.bodySmall,
                                color = DangerRed,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }
                }

                if (filteredUsers.isEmpty()) {
                    item {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = White,
                            border = BorderStroke(1.dp, SubtleOutline),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 20.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.ManageAccounts,
                                    contentDescription = null,
                                    tint = MutedNavyText,
                                    modifier = Modifier.size(22.dp)
                                )
                                Text(
                                    text = "No matching staff accounts",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = NavyText
                                )
                            }
                        }
                    }
                } else {
                    items(filteredUsers, key = { it.uid }) { record ->
                        StaffMemberCard(
                            record = record,
                            isSelfAdmin = record.uid == currentAdminUid,
                            onManageClick = { editingRecord = record }
                        )
                    }
                }
            }
        }
    }

    editingRecord?.let { target ->
        AdminAssignUserDialog(
            record = target,
            isSelfAdmin = target.uid == currentAdminUid,
            branchSuggestions = branchSuggestions,
            onDismiss = { editingRecord = null },
            onSave = { active, branchId, role, staffId ->
                coroutineScope.launch {
                    val result = onSaveUserAccess(target.uid, active, branchId, role, staffId)
                    result.onSuccess {
                        editingRecord = null
                        snackbarHostState.showSnackbar(
                            "Updated ${target.user.fullName}: ${role.displayLabel} · $branchId"
                        )
                    }.onFailure { err ->
                        snackbarHostState.showSnackbar(
                            err.localizedMessage ?: "Failed to update user access."
                        )
                    }
                }
            }
        )
    }
}

@Composable
private fun AdminKpiCell(
    label: String,
    value: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 10.sp,
                letterSpacing = 0.5.sp
            ),
            fontWeight = FontWeight.SemiBold,
            color = MutedNavyText
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium.copy(
                fontSize = 18.sp,
                fontFeatureSettings = "tnum"
            ),
            fontWeight = FontWeight.Bold,
            color = accent
        )
    }
}

@Composable
private fun StaffMemberCard(
    record: StaffUserRecord,
    isSelfAdmin: Boolean,
    onManageClick: () -> Unit
) {
    val user = record.user
    val role = user.resolvedRole()
    val statusBg = if (user.active) SuccessSoftTint else AmberSoftTint
    val statusColor = if (user.active) SuccessGreen else AmberAccent
    val statusText = if (user.active) "ACTIVE" else "PENDING"

    val roleColor = when (role) {
        StaffRole.ADMIN -> CyanDeep
        StaffRole.HEAD_OFFICE -> CyanDeep
        StaffRole.MANAGER -> VaultSlateDeep
        StaffRole.AUDITOR -> CyanPrimary
        StaffRole.CASHIER -> NavyText
    }

    val initials = user.fullName.trim().split(Regex("\\s+"))
        .filter { it.isNotEmpty() }
        .take(2)
        .joinToString("") { it.first().uppercaseChar().toString() }
        .ifBlank { "ST" }

    Surface(
        onClick = onManageClick,
        shape = RoundedCornerShape(12.dp),
        color = White,
        border = BorderStroke(
            width = 1.dp,
            color = if (!user.active) AmberAccent.copy(alpha = 0.45f) else SubtleOutline
        ),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("staff_user_card_${record.uid}")
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 11.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (user.active) SoftSurfaceVariant else AmberSoftTint),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = initials,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (user.active) VaultSlateDeep else AmberAccent
                    )
                }

                Spacer(modifier = Modifier.width(10.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Text(
                            text = user.fullName.ifBlank { "Staff Member" },
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                            fontWeight = FontWeight.Bold,
                            color = NavyText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (isSelfAdmin) {
                            Text(
                                text = "You",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                color = CyanPrimary,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                    Text(
                        text = user.email.ifBlank { record.uid },
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                        color = MutedNavyText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = statusBg
                ) {
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.5.sp),
                        fontWeight = FontWeight.Bold,
                        color = statusColor,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                    )
                }
            }

            HorizontalDivider(thickness = 0.5.dp, color = SubtleOutline)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = role.displayLabel,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.5.sp),
                        fontWeight = FontWeight.Bold,
                        color = roleColor
                    )
                    Text(
                        text = "·",
                        style = MaterialTheme.typography.labelSmall,
                        color = MutedNavyText
                    )
                    Text(
                        text = user.branchId.ifBlank { UNASSIGNED_BRANCH },
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.5.sp),
                        fontWeight = FontWeight.SemiBold,
                        color = NavyText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (user.staffId.isNotBlank() && user.staffId != PENDING_STAFF_ID) {
                        Text(
                            text = "· ${user.staffId}",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 11.sp,
                                fontFeatureSettings = "tnum"
                            ),
                            color = MutedNavyText,
                            maxLines = 1
                        )
                    }
                }

                OutlinedButton(
                    onClick = onManageClick,
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = if (user.active) White else CyanPrimary,
                        contentColor = if (user.active) CyanDeep else White
                    ),
                    border = BorderStroke(1.dp, if (user.active) CyanBorder else CyanPrimary),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                    modifier = Modifier
                        .height(34.dp)
                        .testTag("manage_user_${record.uid}")
                ) {
                    Text(
                        text = if (user.active) "Edit" else "Activate",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.5.sp),
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun AdminAssignUserDialog(
    record: StaffUserRecord,
    isSelfAdmin: Boolean,
    branchSuggestions: List<String>,
    onDismiss: () -> Unit,
    onSave: (active: Boolean, branchId: String, role: StaffRole, staffId: String) -> Unit
) {
    val initialRole = record.user.resolvedRole()
    var active by remember(record.uid) { mutableStateOf(record.user.active) }
    var selectedRole by remember(record.uid) { mutableStateOf(initialRole) }
    var branchId by remember(record.uid) {
        mutableStateOf(
            if (record.user.branchId == UNASSIGNED_BRANCH) "SODO DACHI" else record.user.branchId
        )
    }
    var staffId by remember(record.uid) {
        mutableStateOf(
            if (record.user.staffId == PENDING_STAFF_ID || record.user.staffId.isBlank()) {
                when (initialRole) {
                    StaffRole.AUDITOR -> "AUD001"
                    StaffRole.MANAGER -> "MGR001"
                    StaffRole.HEAD_OFFICE -> "HQ001"
                    StaffRole.ADMIN -> "ADM001"
                    StaffRole.CASHIER -> "CSH001"
                }
            } else {
                record.user.staffId
            }
        )
    }
    var validationError by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = White,
        shape = RoundedCornerShape(16.dp),
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = record.user.fullName.ifBlank { "Assign Access" },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = NavyText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = record.user.email,
                    style = MaterialTheme.typography.labelSmall,
                    color = MutedNavyText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (active) SuccessSoftTint else SoftSurfaceVariant,
                    border = BorderStroke(
                        1.dp,
                        if (active) SuccessGreen.copy(alpha = 0.35f) else SubtleOutline
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Account Status",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                color = NavyText
                            )
                            Text(
                                text = if (active) "Active" else "Pending Activation",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (active) SuccessGreen else AmberAccent,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Switch(
                            checked = active,
                            enabled = !isSelfAdmin,
                            onCheckedChange = { active = it },
                            modifier = Modifier.testTag("admin_user_active_switch")
                        )
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "ROLE",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        fontWeight = FontWeight.Bold,
                        color = MutedNavyText
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        StaffRole.entries.forEach { roleOption ->
                            val isSelected = selectedRole == roleOption
                            FilterChip(
                                selected = isSelected,
                                enabled = !isSelfAdmin || roleOption == StaffRole.ADMIN,
                                onClick = {
                                    selectedRole = roleOption
                                    if (staffId.startsWith("CSH") || staffId.startsWith("AUD") ||
                                        staffId.startsWith("MGR") || staffId.startsWith("HQ") ||
                                        staffId.startsWith("ADM") || staffId == PENDING_STAFF_ID
                                    ) {
                                        staffId = when (roleOption) {
                                            StaffRole.CASHIER -> "CSH001"
                                            StaffRole.AUDITOR -> "AUD001"
                                            StaffRole.MANAGER -> "MGR001"
                                            StaffRole.HEAD_OFFICE -> "HQ001"
                                            StaffRole.ADMIN -> "ADM001"
                                        }
                                    }
                                    if (roleOption == StaffRole.HEAD_OFFICE &&
                                        (branchId == UNASSIGNED_BRANCH || branchId == "SODO DACHI" || branchId.isBlank())
                                    ) {
                                        branchId = "ALL BRANCHES"
                                    }
                                },
                                label = { Text(roleOption.displayLabel) },
                                modifier = Modifier.testTag("admin_role_${roleOption.name}")
                            )
                        }
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(
                        value = branchId,
                        onValueChange = { branchId = it.uppercase(); validationError = null },
                        label = { Text("Branch") },
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("admin_branch_input")
                    )
                    if (branchSuggestions.isNotEmpty()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            branchSuggestions.forEach { preset ->
                                SuggestionChip(
                                    onClick = { branchId = preset; validationError = null },
                                    label = { Text(preset, fontSize = 11.sp) }
                                )
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = staffId,
                    onValueChange = { staffId = it.uppercase(); validationError = null },
                    label = { Text("Staff ID") },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("admin_staff_id_input")
                )

                if (validationError != null) {
                    Text(
                        text = validationError!!,
                        style = MaterialTheme.typography.labelSmall,
                        color = DangerRed
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val cleanBranch = branchId.trim().uppercase()
                    val cleanStaff = staffId.trim().uppercase()
                    if (cleanBranch.isBlank() || cleanBranch == UNASSIGNED_BRANCH) {
                        validationError = "Enter a valid branch."
                        return@Button
                    }
                    if (cleanStaff.isBlank() || cleanStaff == PENDING_STAFF_ID) {
                        validationError = "Enter a valid staff ID."
                        return@Button
                    }
                    onSave(active, cleanBranch, selectedRole, cleanStaff)
                },
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary),
                modifier = Modifier.testTag("admin_save_access_button")
            ) {
                Text("SAVE ACCESS", fontWeight = FontWeight.Bold, color = White)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("CANCEL", color = MutedNavyText)
            }
        }
    )
}
