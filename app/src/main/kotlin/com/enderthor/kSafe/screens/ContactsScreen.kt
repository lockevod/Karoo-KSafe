package com.enderthor.kSafe.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.enderthor.kSafe.R
import com.enderthor.kSafe.activity.MainViewModel
import com.enderthor.kSafe.data.EmergencyContact

@Composable
fun ContactsScreen(vm: MainViewModel) {
    val config by vm.config.collectAsState()
    val contacts = config.contacts

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = stringResource(R.string.contacts_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = stringResource(R.string.contacts_max_info),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(4.dp))

        // Existing contacts
        contacts.forEachIndexed { index, contact ->
            ContactCard(
                contact = contact,
                index = index,
                onUpdate = { vm.updateContact(index, it) },
                onRemove = { vm.removeContact(index) }
            )
        }

        // Add new contact button
        if (contacts.size < 3) {
            Button(
                onClick = { vm.addContact(EmergencyContact()) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.add_contact))
            }
        }
    }
}

@Composable
private fun ContactCard(
    contact: EmergencyContact,
    index: Int,
    onUpdate: (EmergencyContact) -> Unit,
    onRemove: () -> Unit
) {
    var name by remember(contact.name) { mutableStateOf(contact.name) }
    var phone by remember(contact.phone) { mutableStateOf(contact.phone) }
    var email by remember(contact.email) { mutableStateOf(contact.email) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Contact ${index + 1}",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
                OutlinedButton(
                    onClick = onRemove,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.Red)
                ) {
                    Text(stringResource(R.string.remove_contact))
                }
            }

            HorizontalDivider()

            OutlinedTextField(
                value = name,
                onValueChange = {
                    name = it
                    onUpdate(EmergencyContact(name = it, phone = phone, email = email))
                },
                label = { Text(stringResource(R.string.contact_name_hint)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            OutlinedTextField(
                value = phone,
                onValueChange = {
                    phone = it
                    onUpdate(EmergencyContact(name = name, phone = it, email = email))
                },
                label = { Text(stringResource(R.string.contact_phone_hint)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            OutlinedTextField(
                value = email,
                onValueChange = {
                    email = it
                    onUpdate(EmergencyContact(name = name, phone = phone, email = it))
                },
                label = { Text(stringResource(R.string.contact_email_hint)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
        }
    }
}
