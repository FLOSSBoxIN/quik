/*
 * Copyright (C) 2017 Moez Bhatti <moez.bhatti@gmail.com>
 *
 * This file is part of QKSMS.
 *
 * QKSMS is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * QKSMS is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with QKSMS.  If not, see <http://www.gnu.org/licenses/>.
 */
package dev.octoshrimpy.quik.repository

import android.content.ContentUris
import android.content.Context
import android.provider.Telephony
import android.view.View.OnLongClickListener
import dev.octoshrimpy.quik.compat.TelephonyCompat
import dev.octoshrimpy.quik.extensions.anyOf
import dev.octoshrimpy.quik.extensions.asObservable
import dev.octoshrimpy.quik.extensions.map
import dev.octoshrimpy.quik.extensions.removeAccents
import dev.octoshrimpy.quik.filter.ConversationFilter
import dev.octoshrimpy.quik.mapper.CursorToConversation
import dev.octoshrimpy.quik.mapper.CursorToRecipient
import dev.octoshrimpy.quik.model.Contact
import dev.octoshrimpy.quik.model.Conversation
import dev.octoshrimpy.quik.model.Message
import dev.octoshrimpy.quik.model.Recipient
import dev.octoshrimpy.quik.model.SearchResult
import dev.octoshrimpy.quik.util.PhoneNumberUtils
import dev.octoshrimpy.quik.util.tryOrNull
import io.reactivex.Completable
import io.reactivex.Observable
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.schedulers.Schedulers
import io.realm.Case
import io.realm.Realm
import io.realm.RealmResults
import io.realm.Sort
import java.util.concurrent.TimeUnit
import javax.inject.Inject

class ConversationRepositoryImpl @Inject constructor(
    private val context: Context,
    private val conversationFilter: ConversationFilter,
    private val cursorToConversation: CursorToConversation,
    private val cursorToRecipient: CursorToRecipient,
    private val phoneNumberUtils: PhoneNumberUtils
) : ConversationRepository {

    override fun getConversations(unreadAtTop: Boolean, archived: Boolean): RealmResults<Conversation> {
        val sortOrder: MutableList<String> = arrayListOf("pinned", "draft", "lastMessage.date")
        val sortDirections: MutableList<Sort> = arrayListOf(Sort.DESCENDING, Sort.DESCENDING, Sort.DESCENDING)

        if (unreadAtTop) {
            sortOrder.add(0, "lastMessage.read")
            sortDirections.add(0, Sort.ASCENDING)
        }

        return Realm.getDefaultInstance()
                .where(Conversation::class.java)
                .notEqualTo("id", 0L)
                .equalTo("archived", archived)
                .equalTo("blocked", false)
                .isNotEmpty("recipients")
                .beginGroup()
                .isNotNull("lastMessage")
                .or()
                .isNotEmpty("draft")
                .endGroup()
                .sort(
                    sortOrder.toTypedArray(),
                    sortDirections.toTypedArray()
                )
                .findAllAsync()
    }

    override fun getConversationsSnapshot(unreadAtTop: Boolean): List<Conversation> {
        val sortOrder: MutableList<String> = arrayListOf("pinned", "draft", "lastMessage.date")
        val sortDirections: MutableList<Sort> = arrayListOf(Sort.DESCENDING, Sort.DESCENDING, Sort.DESCENDING)

        if (unreadAtTop) {
            sortOrder.add(0, "lastMessage.read")
            sortDirections.add(0, Sort.ASCENDING)
        }

        return Realm.getDefaultInstance().use { realm ->
            realm.refresh()
            realm.copyFromRealm(realm.where(Conversation::class.java)
                    .notEqualTo("id", 0L)
                    .equalTo("archived", false)
                    .equalTo("blocked", false)
                    .isNotEmpty("recipients")
                    .beginGroup()
                    .isNotNull("lastMessage")
                    .or()
                    .isNotEmpty("draft")
                    .endGroup()
                    .sort(
                        sortOrder.toTypedArray(),
                        sortDirections.toTypedArray()
                    )
                    .findAll())
        }
    }

    override fun getTopConversations(): List<Conversation> {
        return Realm.getDefaultInstance().use { realm ->
            realm.copyFromRealm(realm.where(Conversation::class.java)
                    .notEqualTo("id", 0L)
                    .isNotNull("lastMessage")
                    .beginGroup()
                    .equalTo("pinned", true)
                    .or()
                    .greaterThan("lastMessage.date", System.currentTimeMillis() - TimeUnit.DAYS.toMillis(7))
                    .endGroup()
                    .equalTo("archived", false)
                    .equalTo("blocked", false)
                    .isNotEmpty("recipients")
                    .findAll())
                    .sortedWith(compareByDescending<Conversation> { conversation -> conversation.pinned }
                            .thenByDescending { conversation ->
                                realm.where(Message::class.java)
                                        .equalTo("threadId", conversation.id)
                                        .greaterThan("date", System.currentTimeMillis() - TimeUnit.DAYS.toMillis(7))
                                        .count()
                            })
        }
    }

//    override fun setConversationName(id: Long, name: String) {
//        Realm.getDefaultInstance().use { realm ->
//            realm.executeTransaction {
//                realm.where(Conversation::class.java)
//                        .equalTo("id", id)
//                        .findFirst()
//                        ?.name = name
//            }
//        }
//    }
        override fun setConversationName(id: Long, name: String): Completable {
            return Completable.fromAction {
                Realm.getDefaultInstance().use { realm ->
                    realm.executeTransaction {
                        realm.where(Conversation::class.java)
                            .equalTo("id", id)
                            .findFirst()
                            ?.name = name
                    }
                }
            }.subscribeOn(Schedulers.io()) // Ensure the operation is performed on a background thread
        }

    override fun searchConversations(query: CharSequence): List<SearchResult> {
        val realm = Realm.getDefaultInstance()

        val normalizedQuery = query.removeAccents()
        val conversations = realm.copyFromRealm(realm
                .where(Conversation::class.java)
                .notEqualTo("id", 0L)
                .isNotNull("lastMessage")
                .equalTo("blocked", false)
                .isNotEmpty("recipients")
                .sort("pinned", Sort.DESCENDING, "lastMessage.date", Sort.DESCENDING)
                .findAll())

        val messagesByConversation = realm.copyFromRealm(realm
                .where(Message::class.java)
                .beginGroup()
                .contains("body", normalizedQuery, Case.INSENSITIVE)
                .or()
                .contains("parts.text", normalizedQuery, Case.INSENSITIVE)
                .endGroup()
                .findAll())
                .asSequence()
                .groupBy { message -> message.threadId }
                .filter { (threadId, _) -> conversations.firstOrNull { it.id == threadId } != null }
                .map { (threadId, messages) -> Pair(conversations.first { it.id == threadId }, messages.size) }
                .map { (conversation, messages) -> SearchResult(normalizedQuery, conversation, messages) }
                .sortedByDescending { result -> result.messages }
                .toList()

        realm.close()

        return conversations
                .filter { conversation -> conversationFilter.filter(conversation, normalizedQuery) }
                .map { conversation -> SearchResult(normalizedQuery, conversation, 0) } + messagesByConversation
    }

    override fun getBlockedConversations(): RealmResults<Conversation> {
        return Realm.getDefaultInstance()
                .where(Conversation::class.java)
                .equalTo("blocked", true)
                .sort(
                    arrayOf("lastMessage.date"),
                    arrayOf(Sort.DESCENDING)
                )
                .findAll()
    }

    override fun getBlockedConversationsAsync(): RealmResults<Conversation> {
        return Realm.getDefaultInstance()
                .where(Conversation::class.java)
                .equalTo("blocked", true)
                .sort(
                    arrayOf("lastMessage.date"),
                    arrayOf(Sort.DESCENDING)
                )
                .findAllAsync()
    }

    override fun getConversationAsync(threadId: Long): Conversation {
        return Realm.getDefaultInstance()
                .where(Conversation::class.java)
                .equalTo("id", threadId)
                .findFirstAsync()
    }

    override fun getConversation(threadId: Long): Conversation? {
        return Realm.getDefaultInstance()
            .apply { refresh() }
            .where(Conversation::class.java)
            .equalTo("id", threadId)
            .findFirst()
    }

    override fun getUnseenIds(archived: Boolean): List<Long> {
        val conversationIds = ArrayList<Long>()

        Realm.getDefaultInstance()
            .where(Conversation::class.java)
            .notEqualTo("id", 0L)
            .equalTo("archived", archived)
            .equalTo("blocked", false)
            .equalTo("lastMessage.seen", false)
            .sort(
                arrayOf("lastMessage.date"),
                arrayOf(Sort.DESCENDING)
            )
            .findAllAsync()
            .forEach { conversation -> conversationIds.add(conversation.id) }

        return conversationIds
    }

    override fun getUnreadIds(archived: Boolean): List<Long> {
        val conversationIds = ArrayList<Long>()

        Realm.getDefaultInstance()
            .where(Conversation::class.java)
            .notEqualTo("id", 0L)
            .equalTo("archived", archived)
            .equalTo("blocked", false)
            .equalTo("lastMessage.read", false)
            .sort(
                arrayOf("lastMessage.date"),
                arrayOf(Sort.DESCENDING)
            )
            .findAllAsync()
            .forEach { conversation -> conversationIds.add(conversation.id) }

        return conversationIds
    }

    override fun getConversationAndLastSenderContactName(threadId: Long): Pair<Conversation?, String?>? {
        val conversation = Realm.getDefaultInstance()
            .apply { refresh() }
            .where(Conversation::class.java)
            .equalTo("id", threadId)
            .findFirst()

        if (conversation === null)
            return null

        var conversationLastSmsSender: String? = null

        if (conversation !== null) {
            conversationLastSmsSender = conversation?.recipients?.find { recipient ->
                phoneNumberUtils.compare(recipient.address, conversation.lastMessage!!.address)
            }?.contact?.name
        }

        return return Pair(conversation, conversationLastSmsSender)
    }

    override fun getConversations(vararg threadIds: Long): RealmResults<Conversation> {
        return Realm.getDefaultInstance()
                .where(Conversation::class.java)
                .anyOf("id", threadIds)
                .findAll()
    }

    override fun getUnmanagedConversations(): Observable<List<Conversation>> {
        val realm = Realm.getDefaultInstance()
        return realm.where(Conversation::class.java)
                .sort("lastMessage.date", Sort.DESCENDING)
                .notEqualTo("id", 0L)
                .isNotNull("lastMessage")
                .equalTo("archived", false)
                .equalTo("blocked", false)
                .isNotEmpty("recipients")
                .limit(5)
                .findAllAsync()
                .asObservable()
                .filter { it.isLoaded }
                .filter { it.isValid }
                .map { realm.copyFromRealm(it) }
                .subscribeOn(AndroidSchedulers.mainThread())
                .observeOn(Schedulers.io())
    }

    override fun getRecipients(): RealmResults<Recipient> {
        val realm = Realm.getDefaultInstance()
        return realm.where(Recipient::class.java)
                .findAll()
    }

    override fun getUnmanagedRecipients(): Observable<List<Recipient>> {
        val realm = Realm.getDefaultInstance()
        return realm.where(Recipient::class.java)
                .isNotNull("contact")
                .findAllAsync()
                .asObservable()
                .filter { it.isLoaded && it.isValid }
                .map { realm.copyFromRealm(it) }
                .subscribeOn(AndroidSchedulers.mainThread())
    }

    override fun getRecipient(recipientId: Long): Recipient? {
        return Realm.getDefaultInstance()
                .where(Recipient::class.java)
                .equalTo("id", recipientId)
                .findFirst()
    }

    override fun getThreadId(recipient: String): Long? {
        return getThreadId(listOf(recipient))
    }

    override fun getThreadId(recipients: Collection<String>): Long? {
        return Realm.getDefaultInstance().use { realm ->
            realm.refresh()
            realm.where(Conversation::class.java)
                    .findAll()
                    .asSequence()
                    .filter { conversation -> conversation.recipients.size == recipients.size }
                    .find { conversation ->
                        conversation.recipients.map { it.address }.all { address ->
                            recipients.any { recipient -> phoneNumberUtils.compare(recipient, address) }
                        }
                    }?.id
        }
    }

    override fun getOrCreateConversation(threadId: Long): Conversation? {
        return getConversation(threadId) ?: getConversationFromCp(threadId)
    }

    override fun getOrCreateConversation(address: String): Conversation? {
        return getOrCreateConversation(listOf(address))
    }

    override fun getOrCreateConversation(addresses: List<String>): Conversation? {
        if (addresses.isEmpty()) {
            return null
        }

        return (getThreadId(addresses) ?: tryOrNull { TelephonyCompat.getOrCreateThreadId(context, addresses.toSet()) })
                ?.takeIf { threadId -> threadId != 0L }
                ?.let { threadId ->
                    getConversation(threadId)
                            ?.let(Realm.getDefaultInstance()::copyFromRealm)
                            ?: getConversationFromCp(threadId)
                }
    }

    override fun saveDraft(threadId: Long, draft: String) {
        Realm.getDefaultInstance().use { realm ->
            realm.refresh()

            val conversation = realm.where(Conversation::class.java)
                    .equalTo("id", threadId)
                    .findFirst()

            realm.executeTransaction {
                conversation?.takeIf { it.isValid }?.draft = draft
            }
        }
    }

    override fun updateConversations(vararg threadIds: Long) {
        Realm.getDefaultInstance().use { realm ->
            realm.refresh()

            threadIds.forEach { threadId ->
                val conversation = realm
                        .where(Conversation::class.java)
                        .equalTo("id", threadId)
                        .findFirst() ?: return

                val message = realm
                        .where(Message::class.java)
                        .equalTo("threadId", threadId)
                        .sort("date", Sort.DESCENDING)
                        .findFirst()

                realm.executeTransaction {
                    conversation.lastMessage = message
                }
            }
        }
    }

    override fun markArchived(vararg threadIds: Long) {
        Realm.getDefaultInstance().use { realm ->
            val conversations = realm.where(Conversation::class.java)
                    .anyOf("id", threadIds)
                    .findAll()

            realm.executeTransaction {
                conversations.forEach { it.archived = true }
            }
        }
    }

    override fun markUnarchived(vararg threadIds: Long) {
        Realm.getDefaultInstance().use { realm ->
            val conversations = realm.where(Conversation::class.java)
                    .anyOf("id", threadIds)
                    .findAll()

            realm.executeTransaction {
                conversations.forEach { it.archived = false }
            }
        }
    }

    override fun markPinned(vararg threadIds: Long) {
        Realm.getDefaultInstance().use { realm ->
            val conversations = realm.where(Conversation::class.java)
                    .anyOf("id", threadIds)
                    .findAll()

            realm.executeTransaction {
                conversations.forEach { it.pinned = true }
            }
        }
    }

    override fun markUnpinned(vararg threadIds: Long) {
        Realm.getDefaultInstance().use { realm ->
            val conversations = realm.where(Conversation::class.java)
                    .anyOf("id", threadIds)
                    .findAll()

            realm.executeTransaction {
                conversations.forEach { it.pinned = false }
            }
        }
    }

    override fun markBlocked(threadIds: List<Long>, blockingClient: Int, blockReason: String?) {
        Realm.getDefaultInstance().use { realm ->
            val conversations = realm.where(Conversation::class.java)
                    .anyOf("id", threadIds.toLongArray())
                    .equalTo("blocked", false)
                    .findAll()

            realm.executeTransaction {
                conversations.forEach { conversation ->
                    conversation.blocked = true
                    conversation.blockingClient = blockingClient
                    conversation.blockReason = blockReason
                }
            }
        }
    }

    override fun markUnblocked(vararg threadIds: Long) {
        Realm.getDefaultInstance().use { realm ->
            val conversations = realm.where(Conversation::class.java)
                    .anyOf("id", threadIds)
                    .findAll()

            realm.executeTransaction {
                conversations.forEach { conversation ->
                    conversation.blocked = false
                    conversation.blockingClient = null
                    conversation.blockReason = null
                }
            }
        }
    }

    override fun deleteConversations(vararg threadIds: Long) {
        Realm.getDefaultInstance().use { realm ->
            val conversation = realm.where(Conversation::class.java).anyOf("id", threadIds).findAll()
            val messages = realm.where(Message::class.java).anyOf("threadId", threadIds).findAll()

            realm.executeTransaction {
                conversation.deleteAllFromRealm()
                messages.deleteAllFromRealm()
            }
        }

        threadIds.forEach { threadId ->
            val uri = ContentUris.withAppendedId(Telephony.Threads.CONTENT_URI, threadId)
            context.contentResolver.delete(uri, null, null)
        }
    }

    /**
     * Returns a [Conversation] from the system SMS ContentProvider, based on the [threadId]
     *
     * It should be noted that even if we have a valid [threadId], that does not guarantee that
     * we can return a [Conversation]. On some devices, the ContentProvider won't return the
     * conversation unless it contains at least 1 message
     */
    private fun getConversationFromCp(threadId: Long): Conversation? {
        return cursorToConversation.getConversationsCursor()
                ?.map(cursorToConversation::map)
                ?.firstOrNull { it.id == threadId }
                ?.let { conversation ->
                    val realm = Realm.getDefaultInstance()
                    val contacts = realm.copyFromRealm(realm.where(Contact::class.java).findAll())
                    val lastMessage = realm.where(Message::class.java).equalTo("threadId", threadId)
                            .sort("date", Sort.DESCENDING).findFirst()?.let(realm::copyFromRealm)

                    val recipients = conversation.recipients
                            .map { recipient -> recipient.id }
                            .map { id -> cursorToRecipient.getRecipientCursor(id) }
                            .mapNotNull { recipientCursor ->
                                // Map the recipient cursor to a list of recipients
                                recipientCursor?.use { recipientCursor.map { cursorToRecipient.map(recipientCursor) } }
                            }
                            .flatten()
                            .map { recipient ->
                                recipient.apply {
                                    contact = contacts.firstOrNull {
                                        it.numbers.any { phoneNumberUtils.compare(recipient.address, it.address) }
                                    }
                                }
                            }

                    conversation.recipients.clear()
                    conversation.recipients.addAll(recipients)
                    conversation.lastMessage = lastMessage
                    realm.executeTransaction { it.insertOrUpdate(conversation) }
                    realm.close()

                    conversation
                }
    }

}