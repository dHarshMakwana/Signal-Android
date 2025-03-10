package org.thoughtcrime.securesms.mediapreview

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.MarginLayoutParams
import android.widget.Toast
import androidx.core.app.ShareCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2.OFFSCREEN_PAGE_LIMIT_DEFAULT
import androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import org.signal.core.util.concurrent.SignalExecutors
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.animation.DepthPageTransformer2
import org.thoughtcrime.securesms.attachments.DatabaseAttachment
import org.thoughtcrime.securesms.components.ViewBinderDelegate
import org.thoughtcrime.securesms.conversation.mutiselect.forward.MultiselectForwardFragment
import org.thoughtcrime.securesms.conversation.mutiselect.forward.MultiselectForwardFragmentArgs
import org.thoughtcrime.securesms.database.MediaDatabase
import org.thoughtcrime.securesms.databinding.FragmentMediaPreviewV2Binding
import org.thoughtcrime.securesms.mediasend.Media
import org.thoughtcrime.securesms.mediasend.v2.MediaSelectionActivity
import org.thoughtcrime.securesms.mms.GlideApp
import org.thoughtcrime.securesms.mms.PartAuthority
import org.thoughtcrime.securesms.permissions.Permissions
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.util.DateUtils
import org.thoughtcrime.securesms.util.FullscreenHelper
import org.thoughtcrime.securesms.util.LifecycleDisposable
import org.thoughtcrime.securesms.util.MediaUtil
import org.thoughtcrime.securesms.util.SaveAttachmentTask
import org.thoughtcrime.securesms.util.StorageUtil
import java.util.Locale
import java.util.Optional

class MediaPreviewV2Fragment : Fragment(R.layout.fragment_media_preview_v2), MediaPreviewFragment.Events {
  private val TAG = Log.tag(MediaPreviewV2Fragment::class.java)

  private val lifecycleDisposable = LifecycleDisposable()
  private val binding by ViewBinderDelegate(FragmentMediaPreviewV2Binding::bind)
  private val viewModel: MediaPreviewV2ViewModel by viewModels()

  private lateinit var fullscreenHelper: FullscreenHelper

  override fun onAttach(context: Context) {
    super.onAttach(context)
    fullscreenHelper = FullscreenHelper(requireActivity())
  }

  override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
    lifecycleDisposable.bindTo(viewLifecycleOwner)
    return super.onCreateView(inflater, container, savedInstanceState)
  }

  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    super.onViewCreated(view, savedInstanceState)

    val args = MediaIntentFactory.requireArguments(requireArguments())
    initializeViewModel(args)
    initializeToolbar(binding.toolbar)
    initializeViewPager()
    initializeFullScreenUi()
    anchorMarginsToBottomInsets(binding.mediaPreviewDetailsContainer)
    lifecycleDisposable += viewModel.state.distinctUntilChanged().observeOn(AndroidSchedulers.mainThread()).subscribe {
      bindCurrentState(it)
    }
  }

  private fun initializeViewModel(args: MediaIntentFactory.MediaPreviewArgs) {
    if (!MediaUtil.isImageType(args.initialMediaType) && !MediaUtil.isVideoType(args.initialMediaType)) {
      Log.w(TAG, "Unsupported media type sent to MediaPreviewV2Fragment, finishing.")
      Snackbar.make(binding.root, R.string.MediaPreviewActivity_unssuported_media_type, Snackbar.LENGTH_LONG)
        .setAction(R.string.MediaPreviewActivity_dismiss_due_to_error) {
          requireActivity().finish()
        }.show()
    }
    viewModel.initialize(args.showThread, args.allMediaInRail, args.leftIsRecent)
    val sorting = MediaDatabase.Sorting.deserialize(args.sorting)
    viewModel.fetchAttachments(PartAuthority.requireAttachmentId(args.initialMediaUri), args.threadId, sorting)
  }

  private fun initializeToolbar(toolbar: MaterialToolbar) {
    toolbar.setNavigationOnClickListener {
      requireActivity().onBackPressed()
    }

    toolbar.setTitleTextAppearance(requireContext(), R.style.Signal_Text_TitleMedium)
    toolbar.setSubtitleTextAppearance(requireContext(), R.style.Signal_Text_BodyMedium)
    binding.toolbar.inflateMenu(R.menu.media_preview)
  }

  private fun initializeViewPager() {
    binding.mediaPager.offscreenPageLimit = OFFSCREEN_PAGE_LIMIT_DEFAULT
    if (Build.VERSION.SDK_INT >= 21) {
      binding.mediaPager.setPageTransformer(DepthPageTransformer2())
    }
    val adapter = MediaPreviewV2Adapter(this)
    binding.mediaPager.adapter = adapter
    binding.mediaPager.registerOnPageChangeCallback(object : OnPageChangeCallback() {
      override fun onPageSelected(position: Int) {
        super.onPageSelected(position)
        viewModel.setCurrentPage(position)
      }
    })
  }

  private fun initializeAlbumRail(recyclerView: RecyclerView, albumThumbnailMedia: List<Media?>, albumPosition: Int) {
    recyclerView.itemAnimator = null // Or can crash when set to INVISIBLE while animating by FullscreenHelper https://issuetracker.google.com/issues/148720682
    val mediaRailAdapter = MediaRailAdapter(
      GlideApp.with(this),
      object : MediaRailAdapter.RailItemListener {
        override fun onRailItemClicked(distanceFromActive: Int) {
          binding.mediaPager.currentItem += distanceFromActive
        }

        override fun onRailItemDeleteClicked(distanceFromActive: Int) {
          throw UnsupportedOperationException("Callback unsupported.")
        }
      },
      false
    )
    mediaRailAdapter.setMedia(albumThumbnailMedia, albumPosition)
    recyclerView.adapter = mediaRailAdapter
    recyclerView.smoothScrollToPosition(albumPosition)
  }

  private fun initializeFullScreenUi() {
    fullscreenHelper.configureToolbarLayout(binding.toolbarCutoutSpacer, binding.toolbar)
    fullscreenHelper.showAndHideWithSystemUI(requireActivity().window, binding.toolbarLayout, binding.mediaPreviewDetailsContainer)
  }

  private fun bindCurrentState(currentState: MediaPreviewV2State) {
    if (currentState.position == -1 && currentState.mediaRecords.isEmpty()) {
      onMediaNotAvailable()
      return
    }
    when (currentState.loadState) {
      MediaPreviewV2State.LoadState.DATA_LOADED -> bindDataLoadedState(currentState)
      MediaPreviewV2State.LoadState.MEDIA_READY -> bindMediaReadyState(currentState)
      else -> null
    }
  }

  private fun bindDataLoadedState(currentState: MediaPreviewV2State) {
    val currentPosition = currentState.position
    val fragmentAdapter = binding.mediaPager.adapter as MediaPreviewV2Adapter

    fragmentAdapter.setAutoPlayItemPosition(currentPosition)
    fragmentAdapter.updateBackingItems(currentState.mediaRecords.mapNotNull { it.attachment })

    if (binding.mediaPager.currentItem != currentPosition) {
      binding.mediaPager.setCurrentItem(currentPosition, false)
    }
  }

  /**
   * These are binding steps that need a reference to the actual fragment within the pager.
   * This is not available until after a page has been chosen by the ViewPager, and we receive the
   * {@link OnPageChangeCallback}.
   */
  private fun bindMediaReadyState(currentState: MediaPreviewV2State) {
    val currentPosition = currentState.position
    val currentItem: MediaDatabase.MediaRecord = currentState.mediaRecords[currentPosition]

    // pause all other fragments
    childFragmentManager.fragments.map { fragment ->
      if (fragment.tag != "f$currentPosition") {
        (fragment as? MediaPreviewFragment)?.pause()
      }
    }

    val mediaType: MediaPreviewPlayerControlView.MediaMode = if (currentItem.attachment?.isVideoGif == true) {
      MediaPreviewPlayerControlView.MediaMode.IMAGE
    } else {
      MediaPreviewPlayerControlView.MediaMode.fromString(currentItem.contentType)
    }
    binding.mediaPreviewPlaybackControls.setMediaMode(mediaType)

    binding.toolbar.title = getTitleText(currentItem, currentState.showThread)
    binding.toolbar.subtitle = getSubTitleText(currentItem)

    val menu: Menu = binding.toolbar.menu
    if (currentItem.threadId == MediaIntentFactory.NOT_IN_A_THREAD.toLong()) {
      menu.findItem(R.id.delete).isVisible = false
    }

    binding.toolbar.setOnMenuItemClickListener {
      when (it.itemId) {
        R.id.edit -> editMediaItem(currentItem)
        R.id.save -> saveToDisk(currentItem)
        R.id.delete -> deleteMedia(currentItem)
        android.R.id.home -> requireActivity().finish()
        else -> return@setOnMenuItemClickListener false
      }
      return@setOnMenuItemClickListener true
    }
    val albumThumbnailMedia = if (currentState.allMediaInAlbumRail) {
      currentState.mediaRecords.map { it.toMedia() }
    } else {
      currentState.mediaRecords
        .filter { it.attachment != null && it.attachment!!.mmsId == currentItem.attachment?.mmsId }
        .map { it.toMedia() }
    }

    val caption = currentItem.attachment?.caption

    val albumRailEnabled = albumThumbnailMedia.size > 1

    if (caption != null) {
      binding.mediaPreviewCaption.text = caption
      binding.mediaPreviewCaption.visibility = View.VISIBLE
    } else {
      binding.mediaPreviewCaption.visibility = View.GONE
    }

    binding.mediaPreviewPlaybackControls.setShareButtonListener { share(currentItem) }
    binding.mediaPreviewPlaybackControls.setForwardButtonListener { forward(currentItem) }

    val albumRail: RecyclerView = binding.mediaPreviewPlaybackControls.findViewById(R.id.media_preview_album_rail)
    if (albumRailEnabled) {
      val albumPosition = albumThumbnailMedia.indexOfFirst { it?.uri == currentItem.attachment?.uri }
      initializeAlbumRail(albumRail, albumThumbnailMedia, albumPosition)
    }
    albumRail.visibility = if (albumRailEnabled) View.VISIBLE else View.GONE
    val currentFragment: MediaPreviewFragment? = getMediaPreviewFragmentFromChildFragmentManager(currentPosition)
    currentFragment?.setBottomButtonControls(binding.mediaPreviewPlaybackControls)
  }

  private fun getMediaPreviewFragmentFromChildFragmentManager(currentPosition: Int) = childFragmentManager.findFragmentByTag("f$currentPosition") as? MediaPreviewFragment

  private fun getTitleText(mediaRecord: MediaDatabase.MediaRecord, showThread: Boolean): String {
    val recipient: Recipient = Recipient.live(mediaRecord.recipientId).get()
    val defaultFromString: String = if (mediaRecord.isOutgoing) {
      getString(R.string.MediaPreviewActivity_you)
    } else {
      recipient.getDisplayName(requireContext())
    }
    if (!showThread) {
      return defaultFromString
    }

    val threadRecipient = Recipient.live(mediaRecord.threadRecipientId).get()
    return if (mediaRecord.isOutgoing) {
      if (threadRecipient.isSelf) {
        getString(R.string.note_to_self)
      } else {
        getString(R.string.MediaPreviewActivity_you_to_s, threadRecipient.getDisplayName(requireContext()))
      }
    } else {
      if (threadRecipient.isGroup) {
        getString(R.string.MediaPreviewActivity_s_to_s, defaultFromString, threadRecipient.getDisplayName(requireContext()))
      } else {
        getString(R.string.MediaPreviewActivity_s_to_you, defaultFromString)
      }
    }
  }

  private fun getSubTitleText(mediaRecord: MediaDatabase.MediaRecord): String =
    if (mediaRecord.date > 0) {
      DateUtils.getExtendedRelativeTimeSpanString(requireContext(), Locale.getDefault(), mediaRecord.date)
    } else {
      getString(R.string.MediaPreviewActivity_draft)
    }

  private fun anchorMarginsToBottomInsets(viewToAnchor: View) {
    ViewCompat.setOnApplyWindowInsetsListener(viewToAnchor) { view: View, windowInsetsCompat: WindowInsetsCompat ->
      val layoutParams = view.layoutParams as MarginLayoutParams
      layoutParams.setMargins(
        windowInsetsCompat.getSystemWindowInsetLeft(),
        layoutParams.topMargin,
        windowInsetsCompat.getSystemWindowInsetRight(),
        windowInsetsCompat.getSystemWindowInsetBottom()
      )
      view.layoutParams = layoutParams
      windowInsetsCompat
    }
  }

  private fun MediaDatabase.MediaRecord.toMedia(): Media? {
    val attachment = this.attachment
    val uri = attachment?.uri
    if (attachment == null || uri == null) {
      return null
    }

    return Media(
      uri,
      this.contentType,
      this.date,
      attachment.width,
      attachment.height,
      attachment.size,
      0,
      attachment.isBorderless,
      attachment.isVideoGif,
      Optional.empty(),
      Optional.ofNullable(attachment.caption),
      Optional.empty()
    )
  }

  override fun singleTapOnMedia(): Boolean {
    fullscreenHelper.toggleUiVisibility()
    return true
  }

  override fun onMediaNotAvailable() {
    Toast.makeText(requireContext(), R.string.MediaPreviewActivity_media_no_longer_available, Toast.LENGTH_LONG).show()
    requireActivity().finish()
  }

  override fun onMediaReady() {
    viewModel.setMediaReady()
  }

  private fun forward(mediaItem: MediaDatabase.MediaRecord) {
    val attachment = mediaItem.attachment
    val uri = attachment?.uri
    if (attachment != null && uri != null) {
      MultiselectForwardFragmentArgs.create(
        requireContext(),
        mediaItem.threadId,
        uri,
        attachment.contentType
      ) { args: MultiselectForwardFragmentArgs ->
        MultiselectForwardFragment.showBottomSheet(childFragmentManager, args)
      }
    }
  }

  private fun share(mediaItem: MediaDatabase.MediaRecord) {
    val attachment = mediaItem.attachment
    val uri = attachment?.uri
    if (attachment != null && uri != null) {
      val publicUri = PartAuthority.getAttachmentPublicUri(uri)
      val mimeType = Intent.normalizeMimeType(attachment.contentType)
      val shareIntent = ShareCompat.IntentBuilder(requireActivity())
        .setStream(publicUri)
        .setType(mimeType)
        .createChooserIntent()
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

      try {
        startActivity(shareIntent)
      } catch (e: ActivityNotFoundException) {
        Log.w(TAG, "No activity existed to share the media.", e)
        Toast.makeText(requireContext(), R.string.MediaPreviewActivity_cant_find_an_app_able_to_share_this_media, Toast.LENGTH_LONG).show()
      }
    }
  }

  private fun saveToDisk(mediaItem: MediaDatabase.MediaRecord) {
    SaveAttachmentTask.showWarningDialog(requireContext()) { _: DialogInterface?, _: Int ->
      if (StorageUtil.canWriteToMediaStore()) {
        performSaveToDisk(mediaItem)
        return@showWarningDialog
      }
      Permissions.with(this)
        .request(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        .ifNecessary()
        .withPermanentDenialDialog(getString(R.string.MediaPreviewActivity_signal_needs_the_storage_permission_in_order_to_write_to_external_storage_but_it_has_been_permanently_denied))
        .onAnyDenied { Toast.makeText(requireContext(), R.string.MediaPreviewActivity_unable_to_write_to_external_storage_without_permission, Toast.LENGTH_LONG).show() }
        .onAllGranted { performSaveToDisk(mediaItem) }
        .execute()
    }
  }

  fun performSaveToDisk(mediaItem: MediaDatabase.MediaRecord) {
    val saveTask = SaveAttachmentTask(requireContext())
    val saveDate = if (mediaItem.date > 0) mediaItem.date else System.currentTimeMillis()
    val attachment = mediaItem.attachment
    val uri = attachment?.uri
    if (attachment != null && uri != null) {
      saveTask.executeOnExecutor(SignalExecutors.BOUNDED_IO, SaveAttachmentTask.Attachment(uri, attachment.contentType, saveDate, null))
    }
  }

  private fun deleteMedia(mediaItem: MediaDatabase.MediaRecord) {
    val attachment: DatabaseAttachment = mediaItem.attachment ?: return

    MaterialAlertDialogBuilder(requireContext())
      .setIcon(R.drawable.ic_warning)
      .setTitle(R.string.MediaPreviewActivity_media_delete_confirmation_title)
      .setMessage(R.string.MediaPreviewActivity_media_delete_confirmation_message)
      .setCancelable(true)
      .setPositiveButton(R.string.delete) { _, _ ->
        viewModel.deleteItem(requireContext(), attachment, onSuccess = {
          requireActivity().finish()
        }, onError = {
          Log.e(TAG, "Delete failed!", it)
          requireActivity().finish()
        })
      }
      .setNegativeButton(android.R.string.cancel, null)
      .show()
  }

  private fun editMediaItem(currentItem: MediaDatabase.MediaRecord) {
    val media = currentItem.toMedia()
    if (media == null) {
      val rootView = view
      if (rootView != null) {
        Snackbar.make(rootView, R.string.MediaPreviewFragment_edit_media_error, Snackbar.LENGTH_INDEFINITE).show()
      } else {
        Toast.makeText(requireContext(), R.string.MediaPreviewFragment_edit_media_error, Toast.LENGTH_LONG).show()
      }
      return
    }
    startActivity(MediaSelectionActivity.editor(context = requireContext(), media = listOf(media)))
  }

  override fun onPause() {
    super.onPause()
    getMediaPreviewFragmentFromChildFragmentManager(binding.mediaPager.currentItem)?.pause()
  }

  companion object {
    const val ARGS_KEY: String = "args"
  }
}
