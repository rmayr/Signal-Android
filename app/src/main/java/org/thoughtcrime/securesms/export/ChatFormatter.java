package org.thoughtcrime.securesms.export;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.Resources;
import android.database.Cursor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Build;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.annimon.stream.Stream;
import com.google.protobuf.ByteString;

import org.thoughtcrime.securesms.attachments.DatabaseAttachment;
import org.thoughtcrime.securesms.contactshare.Contact;
import org.thoughtcrime.securesms.contactshare.ContactUtil;
import org.thoughtcrime.securesms.database.AttachmentDatabase;
import org.thoughtcrime.securesms.database.DatabaseFactory;
import org.thoughtcrime.securesms.database.MentionUtil;
import org.thoughtcrime.securesms.database.MessageDatabase;
import org.thoughtcrime.securesms.database.MmsDatabase;
import org.thoughtcrime.securesms.database.MmsSmsColumns;
import org.thoughtcrime.securesms.database.MmsSmsDatabase;
import org.thoughtcrime.securesms.database.RecipientDatabase;
import org.thoughtcrime.securesms.database.ThreadBodyUtil;
import org.thoughtcrime.securesms.database.ThreadDatabase;
import org.thoughtcrime.securesms.database.documents.IdentityKeyMismatch;
import org.thoughtcrime.securesms.database.model.MediaMmsMessageRecord;
import org.thoughtcrime.securesms.database.model.Mention;
import org.thoughtcrime.securesms.database.model.MessageRecord;
import org.thoughtcrime.securesms.database.model.MmsMessageRecord;
import org.thoughtcrime.securesms.database.model.Quote;
import org.thoughtcrime.securesms.database.model.ReactionRecord;
import org.thoughtcrime.securesms.database.model.UpdateDescription;
import org.thoughtcrime.securesms.groups.GroupId;
import org.thoughtcrime.securesms.linkpreview.LinkPreview;
import org.thoughtcrime.securesms.media.DecryptableUriMediaInput;
import org.thoughtcrime.securesms.media.MediaInput;
import org.thoughtcrime.securesms.mms.LocationSlide;
import org.thoughtcrime.securesms.mms.Slide;
import org.thoughtcrime.securesms.phonenumbers.PhoneNumberFormatter;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.recipients.RecipientId;
import org.thoughtcrime.securesms.recipients.RecipientUtil;
import org.thoughtcrime.securesms.util.Base64;
import org.thoughtcrime.securesms.util.DateUtils;
import org.thoughtcrime.securesms.util.Util;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXParseException;

import java.io.IOException;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

/**
 * The ChatFormatter Class creates an object that
 * gets the data that the user has selected in the chat export screen,
 * and filters attached media and messages in order to extract the right output
 * for the chat export activity.
 *
 * @author  @anlaji
 * @version 1.0
 * @since   2021-06-20
 */

public class ChatFormatter {

    public static final  String KEY         = "ChatExporter";
    private static final String SCHEMA_PATH = "file:///assets/chatexport.xsdschema/export_chat_xml_schema.xsd";

    private final        long                           threadId;
    private final        Map<String, MediaRecord> selectedMedia;
    private final        Map<String, Uri>               otherFiles;
    private final        Context context;

    private       Document               dom;
    private final Cursor                 conversation;
    private final MmsSmsDatabase.Reader  reader;
    String timePeriod;


    ChatFormatter (@NonNull Context context, long threadId, Date fromDate, Date untilDate) {
        this.context = context;
        this.threadId = threadId;
        this.selectedMedia = new HashMap<> ();
        this.otherFiles = new HashMap<> ();
        MmsSmsDatabase db = DatabaseFactory.getMmsSmsDatabase (context);
        timePeriod =  DateUtils.formatDate ( Resources.getSystem().getConfiguration().locale, atStartOfDay (fromDate).getTime ()) + " - " + DateUtils.formatDate ( Resources.getSystem().getConfiguration().locale, atEndOfDay (untilDate).getTime ());
        int countBeforeStartDate = db.getConversationCount (threadId, atStartOfDay (fromDate).getTime ());
        int countBeforeEndDate = db.getConversationCount (threadId, atEndOfDay (untilDate).getTime ());
        this.conversation = db.getConversation (threadId, db.getMessagePositionOnOrAfterTimestamp (threadId, atEndOfDay (untilDate).getTime ()), countBeforeEndDate - countBeforeStartDate );
        conversation.moveToLast ();
        this.reader = db.readerFor (conversation);
    }

    void closeAll () {
        reader.close ();
        conversation.close ();

    }

    @SuppressLint("LogTagInlined")
    public String parseConversationToXML () {
        // instance of a DocumentBuilderFactory
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance ();
        String finalstring = "";

        try {
            if(conversation.getCount () > 0 && reader.getCurrent () != null)
            {
            // use factory to get an instance of document builder
            DocumentBuilder db = dbf.newDocumentBuilder ();
            // create instance of DOM
            dom = db.newDocument ();

            // create the root element
            Element rootEle = dom.createElement ("chatExport");
            dom.appendChild (dom.createProcessingInstruction (StreamResult.PI_DISABLE_OUTPUT_ESCAPING, "")); // <=== ADD THIS LINE for reading emojis
            dom.appendChild (rootEle);
            dom.appendChild (dom.createProcessingInstruction (StreamResult.PI_ENABLE_OUTPUT_ESCAPING, "")); // <=== ADD THIS LINE
            // create data elements and place them under root
            Element chat = addElement (rootEle, "chat");
            createMembersElem (chat);
            createConversationElem (chat);
             //write the content into xml file
             TransformerFactory transformerFactory = TransformerFactory.newInstance ();
             Transformer transformer = transformerFactory.newTransformer ();
             transformer.setOutputProperty (OutputKeys.INDENT, "yes");
             transformer.setOutputProperty (OutputKeys.METHOD, "xml");
             transformer.setOutputProperty (OutputKeys.ENCODING, "UTF-8");
             transformer.setOutputProperty ("{http://xml.apache.org/xslt}indent-amount", "4");
             DOMSource source = new DOMSource (dom);


             StringWriter outWriter = new StringWriter ();
             StreamResult result = new StreamResult (outWriter);
             transformer.transform (source, result);
             StringBuffer sb = outWriter.getBuffer ();
             finalstring = sb.toString ();
            }

        } catch (ParserConfigurationException | TransformerConfigurationException pce) {
            System.out.println ("UsersXML: Error trying to instantiate DocumentBuilder " + pce);
        } catch (TransformerException transformerException) {
            transformerException.printStackTrace ();
        }
        closeAll ();
        return finalstring;
    }


    private void createMembersElem (Element conv) {
        ThreadDatabase threadDatabase = DatabaseFactory.getThreadDatabase (context);
        RecipientDatabase recipientDatabase = DatabaseFactory.getRecipientDatabase(context);
        Recipient recipient = threadDatabase.getRecipientForThreadId (threadId);
        RecipientDatabase.RecipientSettings settings = recipientDatabase.getRecipientSettings(recipient.getId ());

        Element members = addElement (conv, "members");
        if (settings.getGroupId() != null && recipient.isGroup ()) {
            Recipient groupRecipient = Recipient.resolved (recipient.getId ());
            GroupId groupId = groupRecipient.requireGroupId ();
            List<Recipient> registeredMembers = RecipientUtil.getEligibleForSending (groupRecipient.getParticipants ());


            Element group = addElement (members, "group");
            addAttribute (group, "id", (ByteString.copyFrom(groupId.getDecodedId()).toString ()));
            addElement (group, "title", groupRecipient.getDisplayName (context));
            addElement (group, "number_of_members", String.valueOf (registeredMembers.size ()));

            for (Recipient r : registeredMembers)
                createPersonElem (group, r, settings);

        } else {
            createPersonElem (members, recipient, settings);
            createPersonElem (members, Recipient.self (), settings);
        }
    }

    private void createPersonElem (Element parent, Recipient recipient, RecipientDatabase.RecipientSettings settings) {
        Element contact = addElement (parent, "contact");

        addAttribute (contact, "id", recipient.getId ().toString ());

        addAttribute (contact, "name", recipient.getDisplayName (context));
        if(recipient.hasAUserSetDisplayName (context)) addElement (contact, "profile_name", recipient.getProfileName ().toString ());
        if(recipient.isSelf()){
            addElement (contact, "relation", "self");
        }
        else if(recipient.isSystemContact ()){
            addElement (contact, "relation", "system_contact");
        }
        else if(recipient.isBlocked ()){
            addElement (contact, "relation", "blocked");
        }
        if(recipient.getCombinedAboutAndEmoji ()!=null) addElement (contact, "about", recipient.getCombinedAboutAndEmoji ());
        if (recipient.getEmail ().isPresent ())
            addElement (contact, "email", recipient.getEmail ().get ());
        if (recipient.hasSmsAddress ())
            addElement (contact, "phone", PhoneNumberFormatter.prettyPrint(recipient.getSmsAddress ().get ()));

        Uri systemContactPhoto          = Util.uri(settings.getSystemContactPhotoUri());
        Uri systemContact =      Util.uri(settings.getSystemContactUri ());
        if(systemContactPhoto!=null){
            addElement (contact, "contact_photo_uri", systemContactPhoto.getPath ());
            otherFiles.put (settings.getProfileKey ().toString (), systemContactPhoto);
        }if(systemContact!=null){
            addElement (contact, "contact_uri", systemContact.getPath ());
            otherFiles.put (settings.getProfileKey ().toString (), systemContact);
        }
    }

    private void createConversationElem (Element conv) {

        MessageRecord record = reader.getCurrent ();
        Element records = addElement (conv, "chat_records");
        addAttribute (conv, "selected_time_period", timePeriod);

        Date date, old_date = null;
        String date_, old_date_ = "";
        Element date_log = null;
        do {
            if (!record.isViewOnce ()) {
                date = new Date (record.getDateSent ());
                date_ = new SimpleDateFormat ("dd MMM,yyyy", Resources.getSystem ().getConfiguration ().locale).format (date);

                if (old_date != null)
                    old_date_ = new SimpleDateFormat ("dd MMM,yyyy", Resources.getSystem ().getConfiguration ().locale).format (old_date);

                if ((old_date == null) || !(old_date_.contentEquals (date_))) {
                    old_date = new Date (date.getTime ());
                    date_log = addElement (records, "Log");
                    addAttribute (date_log, "date", date_);
                }

                try {
                    Element turn = addElement (date_log, "turn");
                    Recipient author;
                    if (record.isOutgoing ()) {
                        author = Recipient.self ();
                    } else {
                        author = record.getIndividualRecipient ();
                    }
                    addAttribute (turn, "author", author.getProfileName ().getGivenName ());


                    Element message = addElement (turn, "message");
                    addAttribute (message, MmsSmsColumns.ID, String.valueOf (record.getId ()));
                    if(record.isRemoteDelete ()) addAttribute (message, "is_deleted", String.valueOf (record.isRemoteDelete ()));
                    else {
                        String timestamp_ =  new SimpleDateFormat ("HH:mm", Resources.getSystem ().getConfiguration ().locale).format (new Date (record.getDateSent ()));

                        addAttribute (message, "time", timestamp_);
                        if (record.isOutgoing ())
                            addAttribute (message, "status", getStatusFor (record));

                        Element body = addElement (message, "body");
                        createBodyElem (body, record);
                        if (!record.getReactions ().isEmpty ()) createReactionsElem (body, record);
                        if (!record.getIdentityKeyMismatches ().isEmpty ())
                            addAttribute (message, MmsSmsColumns.MISMATCHED_IDENTITIES, IdentityKeyMismatch.class.getName ());
                        long expires_in = record.getExpiresIn ();
                        if (expires_in > 0)
                            addAttribute (message, MmsSmsColumns.EXPIRES_IN, String.valueOf ((int) (record.getExpiresIn () / 1000)));
                    }
                } catch (Exception e) {
                    System.out.println ("ANGELA XML error by accesing message details: " + e.getMessage () + " " + e.toString ());
                    e.printStackTrace ();
                }
            }
        }
        while ((record = reader.getPrevious ()) != null);
        conversation.close ();
    }

    private void createBodyElem (Element body, MessageRecord record) {
        try {

            if (record.isMms ()) {
                List<Mention> mentions = DatabaseFactory.getMentionDatabase (context).getMentionsForMessage (record.getId ());
                if (!mentions.isEmpty ())
                    createMentions (context, record, mentions, body);
                else if (record.isMms () && !((MmsMessageRecord) record).getSharedContacts ().isEmpty ())
                    createSharedContact(record, body);
                else if (record.isMms () && !((MmsMessageRecord) record).getLinkPreviews ().isEmpty ())
                    createLinkPreviews(record, body);
                else if (record.isMms () && ((MediaMmsMessageRecord)record).getQuote() != null)
                    createQuote(record, body);
                else
                    createMediaContentElem (body, record);
            }
            if(getMessageType (record).contentEquals ("OUTGOING") || getMessageType (record).contentEquals ("PUSH")) createTextElement (body, record);
            else if(getMessageType (record).contentEquals ("CALL_LOG")){
                Element text = addElement (body, "text");
                addElement (text, "type", getCallBody (record));
                createMessageTypeElem (text, record);
            }
            else{
                Element text = addElement (body, "text");
                createMessageTypeElem (text, record);
            }
        }catch (Exception e) {
            e.printStackTrace ();
        }
    }

    private void createQuote (MessageRecord record, Element body) {
        Quote q = ((MediaMmsMessageRecord)record).getQuote();
        Element quote = addElement (body, "quote");
        assert q != null;
        addAttribute (quote, "id", String.valueOf (q.getId ()));
        Recipient author = Recipient.resolved (q.getAuthor ());
        addElement (quote, "author", author.getProfileName ().getGivenName ());
        assert q.getDisplayText () != null;
        addElement (quote, "quote_text",q.getDisplayText ().toString ());
        if (q.isOriginalMissing ())
            addElement (quote, "original", "is_missing");
        if(q.getAttachment ()!= null){
            List<Slide> slides = Stream.of(q.getAttachment ().getSlides()).filter(s -> s.hasImage() || s.hasVideo() || s.hasSticker() || s.hasLocation ()
                    || s.hasAudio () || s.hasDocument () || s.hasPlayOverlay ()).limit(1).toList();
            for(Slide s: slides){
                createAttachmentElem (quote, s);
            }
        }
        else{
            if(q.getDisplayText () == null)
                addElement (quote, "attachment", "is_missing");
        }
        MessageRecord message = DatabaseFactory.getMmsSmsDatabase(context).getMessageFor(q.getId(), author.getId ());
        if(message != null)
            addElement(quote, "timestamp",
                    DateUtils.formatDate ( Resources.getSystem().getConfiguration().locale, message.getTimestamp ()));
        else
            addElement(quote, "timestamp", "Unknown");
        createTextElement (body,record);
    }

    private void createLinkPreviews (MessageRecord record, Element body) {
        for(LinkPreview lp: ((MmsMessageRecord) record).getLinkPreviews ()) {
            Element link = addElement (body, "link");
            addAttribute (link, "title", lp.getTitle ());
            addElement (link, "url", escapeXML (lp.getUrl ()));
            addElement (link, "description", lp.getDescription ());
            if(lp.getAttachmentId ().isValid ()){
                DatabaseAttachment a = DatabaseFactory.getAttachmentDatabase(context).getAttachment (lp.getAttachmentId ());
                Element link_preview = addElement (link, "link_preview");
                MediaRecord mediaRecord = new MediaRecord (a, record.getRecipient ().getId (), threadId, record.getDateSent (), record.isOutgoing ());
                selectedMedia.put (lp.getThumbnail ().get ().getKey (), mediaRecord);
                if(mediaRecord.getAttachment ().getAttachmentId ()!=null)
                    addAttribute (link_preview, "id", mediaRecord.getAttachment ().getAttachmentId ().toString ());
                if(mediaRecord.getAttachment ().getFileName ()!=null)
                    addElement (link_preview, "filename", mediaRecord.getAttachment ().getFileName ());
                if(a.getUri ()!=null)
                    addElement (link_preview, "content_path", getContentPath (mediaRecord.getContentType (), mediaRecord.getDate (), mediaRecord.getAttachment ().getUri ()));
                if(a.getContentType ()!=null)
                    addAttribute (link_preview, "content_type", a.getContentType ());
            }
            if (Build.VERSION.SDK_INT >= 26)
                if(lp.getDate () > 0 )addElement (link, "date", DateUtils.formatDate ( Resources.getSystem().getConfiguration().locale,lp.getDate ()));
        }
    }

    private void createSharedContact (MessageRecord record, Element body) {
        Element shared_contact = addElement (body, "shared_contact");
        Contact contact = ((MmsMessageRecord) record).getSharedContacts ().get (0);
        String displayName = ContactUtil.getStringSummary (context, contact).toString ();
        addElement (shared_contact, "display_name", displayName);
        if(contact.getEmails ().size ()>0)
            for(Contact.Email e: contact.getEmails ()){
                Element email = addElement (shared_contact, "email");
                addAttribute (email, "type", e.getType ().name ());
                if(e.getLabel ()!=null) addElement (email, "label", e.getLabel ());
                addElement (email, "address", e.getEmail ());
            }
        if(contact.getPhoneNumbers ().size ()>0)
            for(Contact.Phone ph: contact.getPhoneNumbers ()) {
                Element phone = addElement (shared_contact, "phone");
                addAttribute (phone, "type", ph.getType ().name ());
                if(ph.getLabel ()!=null) addElement (phone, "label", ph.getLabel ());
                addElement (phone, "number",  ContactUtil.getDisplayNumber (contact, Resources.getSystem().getConfiguration().locale ));
            }
        if(contact.getPostalAddresses ().size ()>0)
            for(Contact.PostalAddress pa: contact.getPostalAddresses ()) {
                Element post = addElement (shared_contact, "postal_address");
                addAttribute (post, "type", pa.getType ().name ());
                if(pa.getLabel ()!=null) addElement (post, "label", pa.getLabel ());
                addElement (post, "street", pa.getStreet ());
                addElement (post, "postal_code", pa.getPostalCode ());
                addElement (post, "neighborhood", pa.getNeighborhood ());
                addElement (post, "po_box", pa.getPoBox ());
                addElement (post, "city", pa.getCity ());
                addElement (post, "country", pa.getCountry ());

            }
    }

    private void createMentions (Context context, MessageRecord record, List<Mention> mentions, Element body) {
        MentionUtil.UpdatedBodyAndMentions updated = MentionUtil.updateBodyAndMentionsWithDisplayNames (context, record.getBody (), mentions);
        mentions = updated.getMentions ();
        for (Mention m : mentions) {
            Element mention = addElement (body, "mention");
            addAttribute (mention, "id", String.valueOf (m.getRecipientId ()));
            addElement (mention, "name", Recipient.resolved (m.getRecipientId ()).getDisplayName (context));
            addElement (mention, "start", String.valueOf (m.getStart ()));
            addElement (mention, "mention_length", String.valueOf (m.getLength ()));
        }
        createTextElement (body, record);
    }

    private void createTextElement (Element body, MessageRecord record) {

        StringBuilder formattedMessageBody;
        formattedMessageBody = new StringBuilder (ThreadBodyUtil.getFormattedBodyFor (context, record));
        //Check if text is BASE_64 and decode it in case
        if (formattedMessageBody.length () % 4 == 0 && formattedMessageBody.toString ().endsWith ("=")){
            try {
                String bodytext = record.getBody ();
                formattedMessageBody = new StringBuilder (String.valueOf (Base64.decode (escapeXML(bodytext))));
            } catch (IOException e) {
                e.printStackTrace ();
            }
            addElement (body, "text", formattedMessageBody.toString ());
        }
        else
            if(!record.getBody ().isEmpty ())
                    addElement (body, "text", escapeXML(record.getBody ()));
    }


    private void createMediaContentElem (Element body, MessageRecord record) {
        try {
            if (((MmsMessageRecord) record).getSlideDeck ().containsMediaSlide ()) {
                Element media = addElement (body, "media_content");
                Element attachment;
                for (Slide s : ((MmsMessageRecord) record).getSlideDeck ().getSlides ()) {
                    attachment = addElement (media, "attachment");
                    createAttachmentElem (attachment, s);
                    MediaRecord mediaRecord = new MediaRecord (((DatabaseAttachment)s.asAttachment()),
                            record.getRecipient ().getId (),
                            threadId,
                            s.asAttachment ().getUploadTimestamp (),
                            record.isOutgoing ());

                    String path = "";
                    if (mediaRecord.getAttachment () != null && !mediaRecord.getAttachment ().hasData ())
                    {
                        addAttribute (attachment, "downloaded", String.valueOf (mediaRecord.getAttachment ().hasData ()));
                    }
                    else if (mediaRecord.getAttachment () != null && mediaRecord.getAttachment ().hasData () && mediaRecord.getAttachment ().getUri () != null) {
                        selectedMedia.put (s.asAttachment ().getKey (), mediaRecord);
                        path = getContentPath (mediaRecord.getContentType (), mediaRecord.getDate (), mediaRecord.getAttachment ().getUri ());
                    }
                    else
                        if (s.getUri () != null)
                            otherFiles.put (s.asAttachment ().getKey (), s.getUri ());
                    addElement (attachment, "content_path", path);
                }
            }
        } catch (Exception e) {
            e.printStackTrace ();
        }
    }

    private String getContentPath (String content_type, long timestamp,@NonNull Uri uri) {
        return ExportZipUtil.getMediaStoreContentPathForType (content_type) +
                ExportZipUtil.generateOutputFileName (content_type, timestamp, uri.getPathSegments ().get (uri.getPathSegments ().size ()-1));
    }

    private void createAttachmentElem (Element attachment, Slide s) {
        try {
            addAttribute (attachment, "id", String.valueOf (((DatabaseAttachment)s.asAttachment()).getAttachmentId()));
            if(s.asAttachment ().getFileName ()!=null)
                addElement (attachment, "filename", s.asAttachment ().getFileName ());
            if(s.asAttachment ().getFastPreflightId ()!=null)
                addElement (attachment, "fast_preflight_id", s.asAttachment ().getFastPreflightId ());
            if(s.asAttachment ().getKey ()!=null)
                addAttribute (attachment, "key", s.asAttachment ().getKey ());

            Element metadata = addElement (attachment, "metadata");
            String name;
            addElement (metadata, "size", Util.getPrettyFileSize (s.getFileSize ()));
            if (s.hasAudio ()) {
                Element audio = addElement (metadata, "audio");
                addAttribute (audio, "content_type", s.getContentType ());
                if (s.getFileName ().isPresent ()) {
                    name = s.getFileName ().get ();
                    addElement (audio, "name", name);
                }
                if (s.asAttachment ().isVoiceNote ())
                    addAttribute (audio, "is", "voice_note");
                if(s.asAttachment ().getCaption ()!=null)
                    addElement (audio, "caption", String.valueOf (s.asAttachment ().getCaption ()));
                MediaInput dataSource;
                if (android.os.Build.VERSION.SDK_INT >= 23) {
                    dataSource = DecryptableUriMediaInput.createForUri(context, s.getUri ());
                    addElement (audio, "duration_sec", String.valueOf (TimeUnit.SECONDS.convert((dataSource.createExtractor ().getTrackFormat (0).getLong(MediaFormat.KEY_DURATION)), TimeUnit.SECONDS)));
                }
            } else if (s.hasVideo ()) {
                Element video = addElement (metadata, "video");
                addAttribute (video, "content_type", s.getContentType ());
                if (s.getFileName ().isPresent ()) {
                    name = s.getFileName ().get ();
                    addElement (video, "name", name);
                }
                addElement (video, "width", String.valueOf (s.asAttachment ().getWidth ()));
                addElement (video, "height", String.valueOf (s.asAttachment ().getHeight ()));
                if(s.asAttachment ().getCaption ()!=null)
                    addElement (video, "caption", String.valueOf (s.asAttachment ().getCaption ()));
                if (s.asAttachment ().getTransformProperties ().isVideoEdited ())
                    addElement (video, "video_edited", "true");
                if (s.asAttachment ().getTransformProperties ().isVideoTrim ())
                    addElement (video, "video_trim", "true");
                MediaInput dataSource;
                if(s.getUri () != null) {
                    if (Build.VERSION.SDK_INT >= 23) {
                        dataSource = DecryptableUriMediaInput.createForUri(context, s.getUri ());
                        addElement (video, "duration_sec", String.valueOf (TimeUnit.SECONDS.convert((dataSource.createExtractor ().getTrackFormat (0).getLong(MediaFormat.KEY_DURATION)), TimeUnit.SECONDS)));
                    }
                    }
                }
            else if(s.hasLocation () && s.asAttachment ().getLocation ()!=null){
                Element location = addElement (metadata, "location");
                addElement (location, "description", String.valueOf( ((LocationSlide)s).getPlace().getDescription ()));
                addElement (location, "latitude", String.valueOf( ((LocationSlide)s).getPlace().getLatLong ().latitude));
                addElement (location, "longitude", String.valueOf( ((LocationSlide)s).getPlace().getLatLong ().longitude));
            }
            else if (s.hasImage ()) {
                Element image;
                image = addElement (metadata, "image");
                addAttribute (image, "content_type", s.getContentType ());
                if (s.getFileName ().isPresent ()) {
                    name = s.getFileName ().get ();
                    addElement (image, "name", name);
                }
                addElement (image, "width", String.valueOf (s.asAttachment ().getWidth ()));
                addElement (image, "height", String.valueOf (s.asAttachment ().getHeight ()));
                if(s.asAttachment ().getCaption ()!=null)
                    addElement (image, "caption", String.valueOf (s.asAttachment ().getCaption ()));
            } else if (s.hasDocument ()) {
                Element document = addElement (metadata, "document");
                addAttribute (document, "content_type", s.getContentType ());
                if (s.getFileName ().isPresent ()) {
                    name = s.getFileName ().get ();
                    addElement (document, "name", name);
                }
            }
            else if (s.hasSticker ()) {
                Element sticker = createStickerElem (metadata, s);
                addAttribute (sticker, "content_type", s.getContentType ());
                if (s.getFileName ().isPresent ()) {
                    name = s.getFileName ().get ();
                    addElement (sticker, "name", name);
                }
            }

            else {
                Element unknown = addElement (metadata, "unknown");
                addAttribute (unknown, "content_type", s.getContentType ());
                if (s.getFileName ().isPresent ()) {
                    name = s.getFileName ().get ();
                    addElement (unknown, "name", name);
                }
            }
            if (s.getBody ().isPresent ()) {
                addElement (attachment, "comment", escapeXML(s.getBody ().get ()));
            }
        } catch (Exception e) {
            e.printStackTrace ();
        }

    }

    private void createReactionsElem (Element body, MessageRecord record) {
        try {
            Element reactions = addElement (body, MmsSmsColumns.REACTIONS);
            for (ReactionRecord rr : record.getReactions ()) {
                Element r = addElement (reactions, "reaction");
                addAttribute (r, "author_id", rr.getAuthor ().toString ());
                addElement (r, "author", Recipient.resolved (rr.getAuthor ()).getDisplayNameOrUsername (context));
                addElement (r, "time", DateUtils.formatDate ( Resources.getSystem().getConfiguration().locale, rr.getDateReceived ()));
                addElement (r, "emogi", rr.getEmoji ());

            }
        } catch (Exception e) {
            e.printStackTrace ();
        }
    }

    private Element createStickerElem (Element media, Slide s) {
        Element sticker = addElement (media, "sticker", s.getContentType ());
        addAttribute (sticker, "id", String.valueOf (Objects.requireNonNull (s.asAttachment ().getSticker ()).getStickerId ()));
        if (s.isBorderless ()) addElement (sticker, "is_borderless", "true");
        addElement (sticker, "emoji", Objects.requireNonNull (s.asAttachment ().getSticker ()).getEmoji ());
        return sticker;
    }

    private String getStatusFor (MessageRecord record) {
        if (record.isRemoteRead ())
            return "READ";
        if (record.isDelivered ())
            return "DELIVERED";
        if (record.isSent ())
            return "SENT";
        if (record.isPending ())
            return "PENDING";
        return "UNKNOWN";
    }

    private void createMessageTypeElem (Element message, MessageRecord record) {

        if (record.isKeyExchange ())
            addElement (message, "msg_type", "KEY_EXCHANGE");
        else if (record.isEndSession ())
            addElement (message, "msg_type", "END_SESSION");
        else if (record.isJoined ())
            addElement (message, "msg_type", "MEMBER_HAS_JOINED");
        else if (record.isIncomingAudioCall ())
            addElement (message, "msg_type", "INCOMING_AUDIO_CALL");
        else if (record.isIncomingVideoCall ())
            addElement (message, "msg_type", "INCOMING_VIDEO_CALL");
        else if (record.isOutgoingAudioCall ())
            addElement (message, "msg_type", "OUTGOING_AUDIO_CALL");
        else if (record.isOutgoingVideoCall ())
            addElement (message, "msg_type", "OUTGOING_VIDEO_CALL");
        else if (record.isMissedAudioCall ())
            addElement (message, "msg_type", "IS_MISSED_AUDIO_CALL");
        else if (record.isVerificationStatusChange ())
            addElement (message, "msg_type", "VERIFICATION_STATUS_CHANGE");
        else if (record.isProfileChange ())
            addElement (message, "msg_type", "PROFILE_CHANGE");
        else if (record.isPendingInsecureSmsFallback ())
            addElement (message, "msg_type", "PENDING_INSECURE_SMS_FALLBACK");
        else if (record.isPending ())
            addElement (message, "msg_type", "PENDING");
        else if (record.isFailed ())
            addElement (message, "msg_type", "FAILED");
        else if (record.isForcedSms ())
            addElement (message, "msg_type", "FORCED_SMS");
        else if (record.isIdentityUpdate ())
            addElement (message, "msg_type", "IDENTITY_UPDATE");
        else if (record.isIdentityDefault ())
            addElement (message, "msg_type", "IDENTITY_DEFAULT");
        else if (record.isIdentityVerified ())
            addElement (message, "msg_type", "IDENTITY_VERIFIED");
        else if (record.isBundleKeyExchange ())
            addElement (message, "msg_type", "BUNDLE_KEY_EXCHANGE");
        else if (record.isContentBundleKeyExchange ())
            addElement (message, "msg_type", "CONTENT_BUNDLE_KEY_ENCHANGE");
        else if (record.isCorruptedKeyExchange ())
            addElement (message, "msg_type", "CORRUPTED_KEY_EXCHANGE");
        else if (record.isInvalidVersionKeyExchange ())
            addElement (message, "msg_type", "INVALID_KEY_EXCHANGE");
        else if (record.isGroupV1MigrationEvent ())
            addElement (message, "msg_type", "GROUP_V1_MIGRATION");
        else if (record.isFailedDecryptionType ())
            addElement (message, "msg_type", "FAILED_DESCRIPTION");
        else if (record.isExpirationTimerUpdate ())
            addElement (message, "msg_type", "EXPIRATION_TIMER_UPDATE");
        else if (record.isSelfCreatedGroup ())
            addElement (message, "msg_type", "SELF_CREATED_GROUP");
        else if (record.isGroupUpdate ()) {
            //checkType (message, record);
            addElement (message, "msg_type", "GROUP_UPDATE");
        }
        else if (record.isGroupQuit ())
            addElement (message, "msg_type", "GROUP_QUIT");
        else if (record.isGroupAction ())
            addElement (message, "msg_type", "GROUP_ACTION");
        else if (record.isCallLog ())
            addElement (message, "msg_type", "CALL_LOG");
        else if (record.isUpdate ())
            addElement (message, "msg_type", "UPDATE");
        else if (record.isGroupV2 ())
            addElement (message, "msg_type", "GROUP_V2");
        else if (record.isPush ())
            addElement (message, "msg_type", "PUSH");
        else if (record.isOutgoing ())
            addElement (message, "msg_type", "OUTGOING");
        else if (record.isSent ())
            addElement (message, "msg_type", "SENT");

        UpdateDescription updateDisplayBody = record.getUpdateDisplayBody (context);
        Collection<UUID> uuids             = updateDisplayBody.getMentioned();
        if(updateDisplayBody.isStringStatic ())
            addElement (message,"system_message", updateDisplayBody.getStaticString ());
    }

    private String getMessageType (MessageRecord record) {
        if (record.isKeyExchange ())
            return "KEY_EXCHANGE";
        else if (record.isEndSession ())
            return"END_SESSION";
        else if (record.isGroupUpdate ())
            return"GROUP_UPDATE";
        else if (record.isSelfCreatedGroup ())
            return "SELF_CREATED_GROUP";
        else if (record.isGroupV2 ())
            return "GROUP_V2";
        else if (record.isGroupQuit ())
            return "GROUP_QUIT";
        else if (record.isGroupAction ())
            return "GROUP_ACTION";
        else if (record.isExpirationTimerUpdate ())
            return "EXPIRATION_TIMER_UPDATE";
        else if (record.isJoined ())
            return "MEMBER_HAS_JOINED";
        else if (record.isIncomingAudioCall ())
            return "INCOMING_AUDIO_CALL";
        else if (record.isIncomingVideoCall ())
            return "INCOMING_VIDEO_CALL";
        else if (record.isOutgoingAudioCall ())
            return "OUTGOING_AUDIO_CALL";
        else if (record.isOutgoingVideoCall ())
            return "OUTGOING_VIDEO_CALL";
        else if (record.isMissedAudioCall ())
            return "IS_MISSED_AUDIO_CALL";
        else if (record.isVerificationStatusChange ())
            return"VERIFICATION_STATUS_CHANGE";
        else if (record.isProfileChange ())
            return "PROFILE_CHANGE";
        else if (record.isPendingInsecureSmsFallback ())
            return"PENDING_INSECURE_SMS_FALLBACK";
        else if (record.isPending ())
            return "PENDING";
        else if (record.isFailed ())
            return "FAILED";
        else if (record.isForcedSms ())
            return "FORCED_SMS";
        else if (record.isPush ())
            return "PUSH";
        else if (record.isIdentityUpdate ())
            return "IDENTITY_UPDATE";
        else if (record.isIdentityDefault ())
            return "IDENTITY_DEFAULT";
        else if (record.isIdentityVerified ())
            return "IDENTITY_VERIFIED";
        else if (record.isBundleKeyExchange ())
            return "BUNDLE_KEY_EXCHANGE";
        else if (record.isContentBundleKeyExchange ())
            return "CONTENT_BUNDLE_KEY_ENCHANGE";
        else if (record.isCorruptedKeyExchange ())
            return "CORRUPTED_KEY_EXCHANGE";
        else if (record.isInvalidVersionKeyExchange ())
            return "INVALID_KEY_EXCHANGE";
        else if (record.isGroupV1MigrationEvent ())
            return "GROUP_V1_MIGRATION";
        else if (record.isFailedDecryptionType ())
            return "FAILED_DESCRIPTION";
        else if (record.isCallLog ())
            return "CALL_LOG";
        else if (record.isOutgoing ())
            return "OUTGOING";
        else if (record.isSent ())
            return "SENT";
        return "UNKNOWN";
    }

    private Element addElement (Element parent, String tagname, String content) {
        Element elem = addElement (parent, tagname);
        elem.setTextContent (content);
        return elem;
    }

    private void addAttribute (Element parent, String tagname, String content) {
        parent.setAttribute (tagname, content);
    }

    private Element addElement (Element parent, String tagname) {
        Element elem = dom.createElement (tagname);
        parent.appendChild (elem);
        return elem;
    }


    private static String getCallBody (MessageRecord record) {
        if (record.isGroupCall ()) return "GROUP CALL";
        else if (record.isIncomingAudioCall ()) return "INCOMING AUDIO CALL";
        else if (record.isIncomingVideoCall ()) return "INCOMING VIDEO CALL";
        else if (record.isMissedAudioCall ()) return "MISSED AUDIO CALL";
        else if (record.isMissedVideoCall ()) return "MISSED VIDEO CALL";
        else if (record.isOutgoingAudioCall ()) return "OUTGOING AUDIO CALL";
        else if (record.isOutgoingVideoCall ()) return "OUTGOING VIDEO CALL";
        else return "UNKNOWN";
    }


    public Map<String, MediaRecord> getAllMedia () {
        return selectedMedia;
    }

    public Map<String, Uri> getOtherFiles () {
        return otherFiles;
    }

   private static String escapeXML (String s) {
        if (TextUtils.isEmpty(s)) return s;
        return s.replaceAll ("&", "&amp;")
                .replaceAll (">", "&gt;")
                .replaceAll ("<", "&lt;")
                .replaceAll ("\"", "&quot;")
                .replaceAll ("'", "&apos;");
    }

    public static Date atStartOfDay(Date date) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(date);
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTime();
    }
    public static Date atEndOfDay(Date date) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(date);
        calendar.set(Calendar.HOUR_OF_DAY, 23);
        calendar.set(Calendar.MINUTE, 59);
        calendar.set(Calendar.SECOND, 59);
        calendar.set(Calendar.MILLISECOND, 999);
        return calendar.getTime();
    }

    public static class MediaRecord {

        private final DatabaseAttachment attachment;
        private final RecipientId        recipientId;
        private final long               threadId;
        private final long               date;
        private final boolean            outgoing;

        private MediaRecord (@Nullable DatabaseAttachment attachment,
                             @NonNull RecipientId recipientId,
                             long threadId,
                             long date,
                             boolean outgoing) {
            this.attachment = attachment;
            this.recipientId = recipientId;
            this.threadId = threadId;
            this.date = date;
            this.outgoing = outgoing;
        }

        public static MediaRecord from (@NonNull Context context, @NonNull Cursor cursor, MessageRecord record) {
            AttachmentDatabase attachmentDatabase = DatabaseFactory.getAttachmentDatabase (context);
            List<DatabaseAttachment> attachments = attachmentDatabase.getAttachment (cursor);
            RecipientId recipientId = RecipientId.from (cursor.getLong (cursor.getColumnIndexOrThrow (MmsDatabase.RECIPIENT_ID)));
            long threadId = cursor.getLong (cursor.getColumnIndexOrThrow (MmsDatabase.THREAD_ID));
            boolean outgoing = MessageDatabase.Types.isOutgoingMessageType (cursor.getLong (cursor.getColumnIndexOrThrow (MmsDatabase.MESSAGE_BOX)));

            long date;

            if (MmsDatabase.Types.isPushType (cursor.getLong (cursor.getColumnIndexOrThrow (MmsDatabase.MESSAGE_BOX))))
                date = record.getDateSent ();
            else
                date = record.getDateReceived ();

            return new MediaRecord (attachments != null && attachments.size () > 0 ? attachments.get (0) : null,
                    recipientId,
                    threadId,
                    date,
                    outgoing);
        }

        public @Nullable
        DatabaseAttachment getAttachment () {
            return attachment;
        }

        public String getContentType () {
            assert attachment != null;
            return attachment.getContentType ();
        }

        public @NonNull
        RecipientId getRecipientId () {
            return recipientId;
        }

        public long getThreadId () {
            return threadId;
        }

        public long getDate () {
            return date;
        }

        public boolean isOutgoing () {
            return outgoing;
        }

        public String getKey () {
            return getAttachment ().getKey ();
        }
    }

}