package org.thoughtcrime.securesms.mms;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import android.text.TextUtils;

import org.session.libsession.messaging.messages.visible.VisibleMessage;
import org.thoughtcrime.securesms.database.ThreadDatabase;
import org.thoughtcrime.securesms.database.documents.IdentityKeyMismatch;
import org.thoughtcrime.securesms.database.documents.NetworkFailure;
import org.session.libsession.messaging.sending_receiving.attachments.Attachment;
import org.session.libsession.messaging.sending_receiving.sharecontacts.Contact;
import org.session.libsession.messaging.sending_receiving.linkpreview.LinkPreview;
import org.session.libsession.messaging.sending_receiving.quotes.QuoteModel;
import org.session.libsession.messaging.threads.recipients.Recipient;

import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

public class OutgoingMediaMessage {

  private   final Recipient                 recipient;
  protected final String                    body;
  protected final List<Attachment>          attachments;
  private   final long                      sentTimeMillis;
  private   final int                       distributionType;
  private   final int                       subscriptionId;
  private   final long                      expiresIn;
  private   final QuoteModel                outgoingQuote;

  private   final List<NetworkFailure>      networkFailures       = new LinkedList<>();
  private   final List<IdentityKeyMismatch> identityKeyMismatches = new LinkedList<>();
  private   final List<Contact>             contacts              = new LinkedList<>();
  private   final List<LinkPreview>         linkPreviews          = new LinkedList<>();

  public OutgoingMediaMessage(Recipient recipient, String message,
                              List<Attachment> attachments, long sentTimeMillis,
                              int subscriptionId, long expiresIn,
                              int distributionType,
                              @Nullable QuoteModel outgoingQuote,
                              @NonNull List<Contact> contacts,
                              @NonNull List<LinkPreview> linkPreviews,
                              @NonNull List<NetworkFailure> networkFailures,
                              @NonNull List<IdentityKeyMismatch> identityKeyMismatches)
  {
    this.recipient             = recipient;
    this.body                  = message;
    this.sentTimeMillis        = sentTimeMillis;
    this.distributionType      = distributionType;
    this.attachments           = attachments;
    this.subscriptionId        = subscriptionId;
    this.expiresIn             = expiresIn;
    this.outgoingQuote         = outgoingQuote;

    this.contacts.addAll(contacts);
    this.linkPreviews.addAll(linkPreviews);
    this.networkFailures.addAll(networkFailures);
    this.identityKeyMismatches.addAll(identityKeyMismatches);
  }

  public OutgoingMediaMessage(Recipient recipient, SlideDeck slideDeck, String message,
                              long sentTimeMillis, int subscriptionId, long expiresIn,
                              int distributionType, @Nullable QuoteModel outgoingQuote,
                              @NonNull List<Contact> contacts,
                              @NonNull List<LinkPreview> linkPreviews)
  {
    this(recipient,
         buildMessage(slideDeck, message),
         slideDeck.asAttachments(),
         sentTimeMillis, subscriptionId,
         expiresIn, distributionType, outgoingQuote,
         contacts, linkPreviews, new LinkedList<>(), new LinkedList<>());
  }

  public OutgoingMediaMessage(OutgoingMediaMessage that) {
    this.recipient           = that.getRecipient();
    this.body                = that.body;
    this.distributionType    = that.distributionType;
    this.attachments         = that.attachments;
    this.sentTimeMillis      = that.sentTimeMillis;
    this.subscriptionId      = that.subscriptionId;
    this.expiresIn           = that.expiresIn;
    this.outgoingQuote       = that.outgoingQuote;

    this.identityKeyMismatches.addAll(that.identityKeyMismatches);
    this.networkFailures.addAll(that.networkFailures);
    this.contacts.addAll(that.contacts);
    this.linkPreviews.addAll(that.linkPreviews);
  }

  public static OutgoingMediaMessage from(VisibleMessage message,
                                          Recipient recipient,
                                          List<Attachment> attachments,
                                          @Nullable QuoteModel outgoingQuote,
                                          @NonNull List<LinkPreview> linkPreviews)
  {
    return new OutgoingMediaMessage(recipient, message.getText(), attachments, message.getSentTimestamp(), -1,
            recipient.getExpireMessages() * 1000, ThreadDatabase.DistributionTypes.DEFAULT, outgoingQuote, Collections.emptyList(),
            linkPreviews, Collections.emptyList(), Collections.emptyList());
  }

  public Recipient getRecipient() {
    return recipient;
  }

  public String getBody() {
    return body;
  }

  public List<Attachment> getAttachments() {
    return attachments;
  }

  public int getDistributionType() {
    return distributionType;
  }

  public boolean isSecure() {
    return false;
  }

  public boolean isGroup() {
    return false;
  }

  public boolean isExpirationUpdate() {
    return false;
  }

  public long getSentTimeMillis() {
    return sentTimeMillis;
  }

  public int getSubscriptionId() {
    return subscriptionId;
  }

  public long getExpiresIn() {
    return expiresIn;
  }

  public @Nullable QuoteModel getOutgoingQuote() {
    return outgoingQuote;
  }

  public @NonNull List<Contact> getSharedContacts() {
    return contacts;
  }

  public @NonNull List<LinkPreview> getLinkPreviews() {
    return linkPreviews;
  }

  public @NonNull List<NetworkFailure> getNetworkFailures() {
    return networkFailures;
  }

  public @NonNull List<IdentityKeyMismatch> getIdentityKeyMismatches() {
    return identityKeyMismatches;
  }

  private static String buildMessage(SlideDeck slideDeck, String message) {
    if (!TextUtils.isEmpty(message)) {
      return message;
    }
    return "";
  }

}
