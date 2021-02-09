@file:Suppress("NAME_SHADOWING")

package org.session.libsession.messaging.sending_receiving

import android.util.Log
import com.google.protobuf.ByteString
import nl.komponents.kovenant.Promise
import nl.komponents.kovenant.deferred

import org.session.libsession.messaging.MessagingConfiguration
import org.session.libsession.messaging.messages.control.ClosedGroupControlMessage
import org.session.libsession.messaging.messages.control.ClosedGroupUpdate
import org.session.libsession.messaging.sending_receiving.notifications.PushNotificationAPI
import org.session.libsession.messaging.sending_receiving.MessageSender.Error
import org.session.libsession.messaging.threads.Address
import org.session.libsession.messaging.threads.recipients.Recipient
import org.session.libsession.utilities.GroupUtil
import org.session.libsession.utilities.TextSecurePreferences
import org.session.libsignal.utilities.Hex

import org.session.libsignal.libsignal.ecc.Curve
import org.session.libsignal.service.internal.push.SignalServiceProtos
import org.session.libsignal.service.loki.protocol.closedgroups.ClosedGroupRatchetCollectionType
import org.session.libsignal.service.loki.protocol.closedgroups.ClosedGroupSenderKey
import org.session.libsignal.service.loki.protocol.closedgroups.SharedSenderKeysImplementation
import org.session.libsignal.service.loki.utilities.hexEncodedPrivateKey
import org.session.libsignal.service.loki.utilities.hexEncodedPublicKey
import org.session.libsignal.service.loki.utilities.removing05PrefixIfNeeded
import org.session.libsignal.utilities.ThreadUtils
import java.util.*

fun MessageSender.createClosedGroup(name: String, members: Collection<String>): Promise<String, Exception> {
    val deferred = deferred<String, Exception>()
    ThreadUtils.queue {
        // Prepare
        val context = MessagingConfiguration.shared.context
        val storage = MessagingConfiguration.shared.storage
        val userPublicKey = storage.getUserPublicKey()!!
        val membersAsData = members.map { ByteString.copyFrom(Hex.fromStringCondensed(it)) }
        // Generate the group's public key
        val groupPublicKey = Curve.generateKeyPair().hexEncodedPublicKey // Includes the "05" prefix
        // Generate the key pair that'll be used for encryption and decryption
        val encryptionKeyPair = Curve.generateKeyPair()
        // Create the group
        val groupID = GroupUtil.doubleEncodeGroupID(groupPublicKey)
        val admins = setOf( userPublicKey )
        val adminsAsData = admins.map { ByteString.copyFrom(Hex.fromStringCondensed(it)) }
        storage.createGroup(groupID, name, LinkedList(members.map { Address.fromSerialized(it) }),
                null, null, LinkedList(admins.map { Address.fromSerialized(it) }))
        storage.setProfileSharing(Address.fromSerialized(groupID), true)
        // Send a closed group update message to all members individually
        val closedGroupUpdateKind = ClosedGroupControlMessage.Kind.New(ByteString.copyFrom(Hex.fromStringCondensed(groupPublicKey)), name, encryptionKeyPair, membersAsData, adminsAsData)
        for (member in members) {
            if (member == userPublicKey) { continue }
            val closedGroupControlMessage = ClosedGroupControlMessage(closedGroupUpdateKind)
            sendNonDurably(closedGroupControlMessage, Address.fromSerialized(groupID)).get()
        }
        // Add the group to the user's set of public keys to poll for
        storage.addClosedGroupPublicKey(groupPublicKey)
        // Store the encryption key pair
        storage.addClosedGroupEncryptionKeyPair(encryptionKeyPair, groupPublicKey)
        // Notify the user
        val threadID = storage.getOrCreateThreadIdFor(Address.fromSerialized(groupID))
        storage.insertOutgoingInfoMessage(context, groupID, SignalServiceProtos.GroupContext.Type.UPDATE, name, members, admins, threadID)
        // Notify the PN server
        PushNotificationAPI.performOperation(PushNotificationAPI.ClosedGroupOperation.Subscribe, groupPublicKey, userPublicKey)
        // Fulfill the promise
        deferred.resolve(groupID)
    }
    // Return
    return deferred.promise
}

fun MessageSender.v2_update(groupPublicKey: String, members: List<String>, name: String) {
    val context = MessagingConfiguration.shared.context
    val storage = MessagingConfiguration.shared.storage
    val groupID = GroupUtil.doubleEncodeGroupID(groupPublicKey)
    val group = storage.getGroup(groupID) ?: run {
        Log.d("Loki", "Can't update nonexistent closed group.")
        throw Error.NoThread
    }
    // Update name if needed
    if (name != group.title) { setName(groupPublicKey, name) }
    // Add members if needed
    val addedMembers = members - group.members.map { it.serialize() }
    if (!addedMembers.isEmpty()) { addMembers(groupPublicKey, addedMembers) }
    // Remove members if needed
    val removedMembers = group.members.map { it.serialize() } - members
    if (removedMembers.isEmpty()) { removeMembers(groupPublicKey, removedMembers) }
}

fun MessageSender.setName(groupPublicKey: String, newName: String) {
    val context = MessagingConfiguration.shared.context
    val storage = MessagingConfiguration.shared.storage
    val groupID = GroupUtil.doubleEncodeGroupID(groupPublicKey)
    val group = storage.getGroup(groupID) ?: run {
        Log.d("Loki", "Can't change name for nonexistent closed group.")
        throw Error.NoThread
    }
    val members = group.members.map { it.serialize() }.toSet()
    val admins = group.admins.map { it.serialize() }
    // Send the update to the group
    val kind = ClosedGroupControlMessage.Kind.NameChange(newName)
    val closedGroupControlMessage = ClosedGroupControlMessage(kind)
    send(closedGroupControlMessage, Address.fromSerialized(groupID))
    // Update the group
    storage.updateTitle(groupID, newName)
    // Notify the user
    val infoType = SignalServiceProtos.GroupContext.Type.UPDATE
    val threadID = storage.getOrCreateThreadIdFor(Address.fromSerialized(groupID))
    storage.insertOutgoingInfoMessage(context, groupID, infoType, newName, members, admins, threadID)
}

fun MessageSender.addMembers(groupPublicKey: String, membersToAdd: List<String>) {
    val context = MessagingConfiguration.shared.context
    val storage = MessagingConfiguration.shared.storage
    val groupID = GroupUtil.doubleEncodeGroupID(groupPublicKey)
    val group = storage.getGroup(groupID) ?: run {
        Log.d("Loki", "Can't add members to nonexistent closed group.")
        throw Error.NoThread
    }
    if (membersToAdd.isEmpty()) {
        Log.d("Loki", "Invalid closed group update.")
        throw Error.InvalidClosedGroupUpdate
    }
    val updatedMembers = group.members.map { it.serialize() }.toSet() + membersToAdd
    // Save the new group members
    storage.updateMembers(groupID, updatedMembers.map { Address.fromSerialized(it) })
    val membersAsData = updatedMembers.map { ByteString.copyFrom(Hex.fromStringCondensed(it)) }
    val newMembersAsData = membersToAdd.map { ByteString.copyFrom(Hex.fromStringCondensed(it)) }
    val admins = group.admins.map { it.serialize() }
    val adminsAsData = admins.map { ByteString.copyFrom(Hex.fromStringCondensed(it)) }
    val encryptionKeyPair = storage.getLatestClosedGroupEncryptionKeyPair(groupPublicKey) ?: run {
        Log.d("Loki", "Couldn't get encryption key pair for closed group.")
        throw Error.NoKeyPair
    }
    val name = group.title
    // Send the update to the group
    val memberUpdateKind = ClosedGroupControlMessage.Kind.MembersAdded(newMembersAsData)
    val closedGroupControlMessage = ClosedGroupControlMessage(memberUpdateKind)
    send(closedGroupControlMessage, Address.fromSerialized(groupID))
    // Send closed group update messages to any new members individually
    for (member in membersToAdd) {
        val closedGroupNewKind = ClosedGroupControlMessage.Kind.New(ByteString.copyFrom(Hex.fromStringCondensed(groupPublicKey)), name, encryptionKeyPair, membersAsData, adminsAsData)
        val closedGroupControlMessage = ClosedGroupControlMessage(closedGroupNewKind)
        send(closedGroupControlMessage, Address.fromSerialized(member))
    }
    // Notify the user
    val infoType = SignalServiceProtos.GroupContext.Type.UPDATE
    val threadID = storage.getOrCreateThreadIdFor(Address.fromSerialized(groupID))
    storage.insertOutgoingInfoMessage(context, groupID, infoType, name, updatedMembers, admins, threadID)
}

fun MessageSender.removeMembers(groupPublicKey: String, membersToRemove: List<String>) {
    val context = MessagingConfiguration.shared.context
    val storage = MessagingConfiguration.shared.storage
    val userPublicKey = storage.getUserPublicKey()!!
    val groupID = GroupUtil.doubleEncodeGroupID(groupPublicKey)
    val group = storage.getGroup(groupID) ?: run {
        Log.d("Loki", "Can't remove members from nonexistent closed group.")
        throw Error.NoThread
    }
    if (membersToRemove.isEmpty()) {
        Log.d("Loki", "Invalid closed group update.")
        throw Error.InvalidClosedGroupUpdate
    }
    val updatedMembers = group.members.map { it.serialize() }.toSet() - membersToRemove
    // Save the new group members
    storage.updateMembers(groupID, updatedMembers.map { Address.fromSerialized(it) })
    val removeMembersAsData = membersToRemove.map { ByteString.copyFrom(Hex.fromStringCondensed(it)) }
    val admins = group.admins.map { it.serialize() }
    if (membersToRemove.any { it in admins } && updatedMembers.isNotEmpty()) {
        Log.d("Loki", "Can't remove admin from closed group unless the group is destroyed entirely.")
        throw Error.InvalidClosedGroupUpdate
    }
    val name = group.title
    // Send the update to the group
    val memberUpdateKind = ClosedGroupControlMessage.Kind.MembersRemoved(removeMembersAsData)
    val closedGroupControlMessage = ClosedGroupControlMessage(memberUpdateKind)
    send(closedGroupControlMessage, Address.fromSerialized(groupID))
    val isCurrentUserAdmin = admins.contains(userPublicKey)
    if (isCurrentUserAdmin) {
        generateAndSendNewEncryptionKeyPair(groupPublicKey, updatedMembers)
    }
    // Notify the user
    val infoType = SignalServiceProtos.GroupContext.Type.UPDATE
    val threadID = storage.getOrCreateThreadIdFor(Address.fromSerialized(groupID))
    storage.insertOutgoingInfoMessage(context, groupID, infoType, name, updatedMembers, admins, threadID)
}

fun MessageSender.v2_leave(groupPublicKey: String) {
    val context = MessagingConfiguration.shared.context
    val storage = MessagingConfiguration.shared.storage
    val userPublicKey = storage.getUserPublicKey()!!
    val groupID = GroupUtil.doubleEncodeGroupID(groupPublicKey)
    val group = storage.getGroup(groupID) ?: run {
        Log.d("Loki", "Can't leave nonexistent closed group.")
        throw Error.NoThread
    }
    val updatedMembers = group.members.map { it.serialize() }.toSet() - userPublicKey
    val admins = group.admins.map { it.serialize() }
    val name = group.title
    // Send the update to the group
    val closedGroupControlMessage = ClosedGroupControlMessage(ClosedGroupControlMessage.Kind.MemberLeft)
    sendNonDurably(closedGroupControlMessage, Address.fromSerialized(groupID)).success {
        // Remove the group private key and unsubscribe from PNs
        MessageReceiver.disableLocalGroupAndUnsubscribe(groupPublicKey, groupID, userPublicKey)
    }
    // Notify the user
    val infoType = SignalServiceProtos.GroupContext.Type.QUIT
    val threadID = storage.getOrCreateThreadIdFor(Address.fromSerialized(groupID))
    storage.insertOutgoingInfoMessage(context, groupID, infoType, name, updatedMembers, admins, threadID)
}

fun MessageSender.update(groupPublicKey: String, members: Collection<String>, name: String): Promise<Unit, Exception> {
    val deferred = deferred<Unit, Exception>()
    val context = MessagingConfiguration.shared.context
    val storage = MessagingConfiguration.shared.storage
    val userPublicKey = storage.getUserPublicKey()!!
    val sskDatabase = MessagingConfiguration.shared.sskDatabase
    val groupID = GroupUtil.getEncodedClosedGroupID(GroupUtil.getEncodedClosedGroupID(Hex.fromStringCondensed(groupPublicKey)).toByteArray()) // double encoded
    val group = storage.getGroup(groupID)
    if (group == null) {
        Log.d("Loki", "Can't update nonexistent closed group.")
        deferred.reject(Error.NoThread)
        return deferred.promise
    }
    val oldMembers = group.members.map { it.serialize() }.toSet()
    val newMembers = members.minus(oldMembers)
    val membersAsData = members.map { Hex.fromStringCondensed(it) }
    val admins = group.admins.map { it.serialize() }
    val adminsAsData = admins.map { Hex.fromStringCondensed(it) }
    val groupPrivateKey = sskDatabase.getClosedGroupPrivateKey(groupPublicKey)
    if (groupPrivateKey == null) {
        Log.d("Loki", "Couldn't get private key for closed group.")
        deferred.reject(Error.NoPrivateKey)
        return deferred.promise
    }
    val wasAnyUserRemoved = members.toSet().intersect(oldMembers) != oldMembers.toSet()
    val removedMembers = oldMembers.minus(members)
    val isUserLeaving = removedMembers.contains(userPublicKey)
    val newSenderKeys: List<ClosedGroupSenderKey>
    if (wasAnyUserRemoved) {
        if (isUserLeaving && removedMembers.count() != 1) {
            Log.d("Loki", "Can't remove self and others simultaneously.")
            deferred.reject(Error.InvalidClosedGroupUpdate)
            return deferred.promise
        }
        // Send the update to the existing members using established channels (don't include new ratchets as everyone should regenerate new ratchets individually)
        val promises = oldMembers.map { member ->
            val closedGroupUpdateKind = ClosedGroupUpdate.Kind.Info(Hex.fromStringCondensed(groupPublicKey),
                    name, setOf(), membersAsData, adminsAsData)
            val closedGroupUpdate = ClosedGroupUpdate()
            closedGroupUpdate.kind = closedGroupUpdateKind
            val address = Address.fromSerialized(member)
            MessageSender.sendNonDurably(closedGroupUpdate, address).get()
        }

        val allOldRatchets = sskDatabase.getAllClosedGroupRatchets(groupPublicKey, ClosedGroupRatchetCollectionType.Current)
        for (pair in allOldRatchets) {
            val senderPublicKey = pair.first
            val ratchet = pair.second
            val collection = ClosedGroupRatchetCollectionType.Old
            sskDatabase.setClosedGroupRatchet(groupPublicKey, senderPublicKey, ratchet, collection)
        }
        // Delete all ratchets (it's important that this happens * after * sending out the update)
        sskDatabase.removeAllClosedGroupRatchets(groupPublicKey, ClosedGroupRatchetCollectionType.Current)
        // Remove the group from the user's set of public keys to poll for if the user is leaving. Otherwise generate a new ratchet and
        // send it out to all members (minus the removed ones) using established channels.
        if (isUserLeaving) {
            sskDatabase.removeClosedGroupPrivateKey(groupPublicKey)
            storage.setActive(groupID, false)
            storage.removeMember(groupID, Address.fromSerialized(userPublicKey))
            // Notify the PN server
            PushNotificationAPI.performOperation(PushNotificationAPI.ClosedGroupOperation.Unsubscribe, groupPublicKey, userPublicKey)
        } else {
            // Send closed group update messages to any new members using established channels
            for (member in newMembers) {
                val closedGroupUpdateKind = ClosedGroupUpdate.Kind.New(Hex.fromStringCondensed(groupPublicKey), name,
                        Hex.fromStringCondensed(groupPrivateKey), listOf(), membersAsData, adminsAsData)
                val closedGroupUpdate = ClosedGroupUpdate()
                closedGroupUpdate.kind = closedGroupUpdateKind
                val address = Address.fromSerialized(member)
                MessageSender.sendNonDurably(closedGroupUpdate, address)
            }
            // Send out the user's new ratchet to all members (minus the removed ones) using established channels
            val userRatchet = SharedSenderKeysImplementation.shared.generateRatchet(groupPublicKey, userPublicKey)
            val userSenderKey = ClosedGroupSenderKey(Hex.fromStringCondensed(userRatchet.chainKey), userRatchet.keyIndex, Hex.fromStringCondensed(userPublicKey))
            for (member in members) {
                if (member == userPublicKey) { continue }
                val closedGroupUpdateKind = ClosedGroupUpdate.Kind.SenderKey(Hex.fromStringCondensed(groupPublicKey), userSenderKey)
                val closedGroupUpdate = ClosedGroupUpdate()
                closedGroupUpdate.kind = closedGroupUpdateKind
                val address = Address.fromSerialized(member)
                MessageSender.sendNonDurably(closedGroupUpdate, address)
            }
        }
    } else if (newMembers.isNotEmpty()) {
        // Generate ratchets for any new members
        newSenderKeys = newMembers.map { publicKey ->
            val ratchet = SharedSenderKeysImplementation.shared.generateRatchet(groupPublicKey, publicKey)
            ClosedGroupSenderKey(Hex.fromStringCondensed(ratchet.chainKey), ratchet.keyIndex, Hex.fromStringCondensed(publicKey))
        }
        // Send a closed group update message to the existing members with the new members' ratchets (this message is aimed at the group)
        val closedGroupUpdateKind = ClosedGroupUpdate.Kind.Info(Hex.fromStringCondensed(groupPublicKey), name,
                newSenderKeys, membersAsData, adminsAsData)
        val closedGroupUpdate = ClosedGroupUpdate()
        closedGroupUpdate.kind = closedGroupUpdateKind
        val address = Address.fromSerialized(groupID)
        MessageSender.send(closedGroupUpdate, address)
        // Send closed group update messages to the new members using established channels
        var allSenderKeys = sskDatabase.getAllClosedGroupSenderKeys(groupPublicKey, ClosedGroupRatchetCollectionType.Current)
        allSenderKeys = allSenderKeys.union(newSenderKeys)
        for (member in newMembers) {
            val closedGroupUpdateKind = ClosedGroupUpdate.Kind.New(Hex.fromStringCondensed(groupPublicKey), name,
                    Hex.fromStringCondensed(groupPrivateKey), allSenderKeys, membersAsData, adminsAsData)
            val closedGroupUpdate = ClosedGroupUpdate()
            closedGroupUpdate.kind = closedGroupUpdateKind
            val address = Address.fromSerialized(member)
            MessageSender.send(closedGroupUpdate, address)
        }
    } else {
        val allSenderKeys = sskDatabase.getAllClosedGroupSenderKeys(groupPublicKey, ClosedGroupRatchetCollectionType.Current)
        val closedGroupUpdateKind = ClosedGroupUpdate.Kind.Info(Hex.fromStringCondensed(groupPublicKey), name,
                allSenderKeys, membersAsData, adminsAsData)
        val closedGroupUpdate = ClosedGroupUpdate()
        closedGroupUpdate.kind = closedGroupUpdateKind
        val address = Address.fromSerialized(groupID)
        MessageSender.send(closedGroupUpdate, address)
    }
    // Update the group
    storage.updateTitle(groupID, name)
    if (!isUserLeaving) {
        // The call below sets isActive to true, so if the user is leaving we have to use groupDB.remove(...) instead
        storage.updateMembers(groupID, members.map { Address.fromSerialized(it) })
    }
    // Notify the user
    val infoType = if (isUserLeaving) SignalServiceProtos.GroupContext.Type.QUIT else SignalServiceProtos.GroupContext.Type.UPDATE
    val threadID = storage.getOrCreateThreadIdFor(Address.fromSerialized(groupID))
    storage.insertOutgoingInfoMessage(context, groupID, infoType, name, members, admins, threadID)
    deferred.resolve(Unit)
    return deferred.promise
}

fun MessageSender.leave(groupPublicKey: String) {
    val storage = MessagingConfiguration.shared.storage
    val userPublicKey = storage.getUserPublicKey()!!
    val groupID = GroupUtil.getEncodedClosedGroupID(GroupUtil.getEncodedClosedGroupID(Hex.fromStringCondensed(groupPublicKey)).toByteArray()) // double encoded
    val group = storage.getGroup(groupID)
    if (group == null) {
        Log.d("Loki", "Can't leave nonexistent closed group.")
        return
    }
    val name = group.title
    val oldMembers = group.members.map { it.serialize() }.toSet()
    val newMembers = oldMembers.minus(userPublicKey)
    return update(groupPublicKey, newMembers, name).get()
}

fun MessageSender.generateAndSendNewEncryptionKeyPair(groupPublicKey: String, targetMembers: Collection<String>) {
    // Prepare
    val storage = MessagingConfiguration.shared.storage
    val userPublicKey = storage.getUserPublicKey()!!
    val groupID = GroupUtil.doubleEncodeGroupID(groupPublicKey)
    val group = storage.getGroup(groupID) ?: run {
        Log.d("Loki", "Can't update nonexistent closed group.")
        throw Error.NoThread
    }
    if (!group.admins.map { it.toString() }.contains(userPublicKey)) {
        Log.d("Loki", "Can't distribute new encryption key pair as non-admin.")
        throw Error.InvalidClosedGroupUpdate
    }
    // Generate the new encryption key pair
    val newKeyPair = Curve.generateKeyPair()
    // Distribute it
    val proto = SignalServiceProtos.KeyPair.newBuilder()
    proto.publicKey = ByteString.copyFrom(newKeyPair.publicKey.serialize().removing05PrefixIfNeeded())
    proto.privateKey = ByteString.copyFrom(newKeyPair.privateKey.serialize())
    val plaintext = proto.build().toByteArray()
    val wrappers = targetMembers.map { publicKey ->
        val ciphertext = MessageSenderEncryption.encryptWithSessionProtocol(plaintext, publicKey)
        ClosedGroupControlMessage.KeyPairWrapper(publicKey, ByteString.copyFrom(ciphertext))
    }
    val kind = ClosedGroupControlMessage.Kind.EncryptionKeyPair(wrappers)
    val closedGroupControlMessage = ClosedGroupControlMessage(kind)
    sendNonDurably(closedGroupControlMessage, Address.fromSerialized(groupID)).success {
        // Store it * after * having sent out the message to the group
        storage.addClosedGroupEncryptionKeyPair(newKeyPair, groupPublicKey)
    }
}