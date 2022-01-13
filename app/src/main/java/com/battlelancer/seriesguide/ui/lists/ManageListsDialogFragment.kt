package com.battlelancer.seriesguide.ui.lists

import android.content.Context
import android.database.Cursor
import android.os.Bundle
import android.util.SparseBooleanArray
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.Button
import android.widget.Checkable
import android.widget.CheckedTextView
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatDialogFragment
import androidx.cursoradapter.widget.CursorAdapter
import androidx.fragment.app.FragmentManager
import androidx.loader.app.LoaderManager
import androidx.loader.content.CursorLoader
import androidx.loader.content.Loader
import com.battlelancer.seriesguide.R
import com.battlelancer.seriesguide.provider.SeriesGuideContract.ListItemTypes
import com.battlelancer.seriesguide.provider.SeriesGuideContract.ListItems
import com.battlelancer.seriesguide.provider.SeriesGuideContract.Lists
import com.battlelancer.seriesguide.provider.SeriesGuideDatabase.Tables
import com.battlelancer.seriesguide.provider.SgRoomDatabase.Companion.getInstance
import com.battlelancer.seriesguide.util.safeShow
import java.util.ArrayList

/**
 * Displays a dialog displaying all user created lists,
 * allowing to add or remove the given show for any.
 */
class ManageListsDialogFragment : AppCompatDialogFragment(), LoaderManager.LoaderCallbacks<Cursor>,
    AdapterView.OnItemClickListener {

    private var listView: ListView? = null
    private lateinit var adapter: ListsAdapter
    private var showId: Long = 0
    private var showTmdbId = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showId = requireArguments().getLong(ARG_LONG_SHOW_ID)

        // hide title, use custom theme
        setStyle(STYLE_NO_TITLE, 0)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val layout = inflater.inflate(R.layout.dialog_manage_lists, container, false)

        // buttons
        val dontAddButton = layout.findViewById<Button>(R.id.buttonNegative)
        dontAddButton.setText(android.R.string.cancel)
        dontAddButton.setOnClickListener { dismiss() }
        val addButton = layout.findViewById<Button>(R.id.buttonPositive)
        addButton.setText(android.R.string.ok)
        addButton.setOnClickListener {
            // add item to selected lists, remove from previously selected lists
            val checkedLists = adapter.checkedPositions
            val addToTheseLists: MutableList<String> = ArrayList()
            val removeFromTheseLists: MutableList<String> = ArrayList()
            for (position in 0 until adapter.count) {
                val listEntry = adapter.getItem(position) as Cursor

                val wasListChecked = !listEntry.getString(ListsQuery.LIST_ITEM_ID).isNullOrEmpty()
                val isListChecked = checkedLists[position]

                val listId = listEntry.getString(ListsQuery.LIST_ID)
                if (listId.isNullOrEmpty()) {
                    continue  // skip, no id
                }
                if (wasListChecked && !isListChecked) {
                    // remove from list
                    removeFromTheseLists.add(listId)
                } else if (!wasListChecked && isListChecked) {
                    // add to list
                    addToTheseLists.add(listId)
                }
            }
            ListsTools.changeListsOfItem(
                requireContext(), showTmdbId,
                ListItemTypes.TMDB_SHOW, addToTheseLists, removeFromTheseLists
            )
            dismiss()
        }

        // lists list
        listView = layout.findViewById(R.id.list)
        /*
         * As using CHOICE_MODE_MULTIPLE does not seem to work before Jelly
         * Bean, do everything ourselves.
         */
        listView!!.onItemClickListener = this
        return layout
    }

    override fun onActivityCreated(args: Bundle?) {
        super.onActivityCreated(args)
        adapter = ListsAdapter(requireContext())
        listView!!.adapter = adapter

        val showDetails = getInstance(requireContext()).sgShow2Helper().getShowMinimal(showId)
        if (showDetails?.tmdbId == null || showDetails.tmdbId == 0) {
            dismiss()
            return
        }
        showTmdbId = showDetails.tmdbId

        // display item title
        val itemTitle = requireView().findViewById<TextView>(R.id.item)
        itemTitle.text = showDetails.title

        // load data
        LoaderManager.getInstance(this).initLoader(0, null, this)
    }

    override fun onItemClick(parent: AdapterView<*>?, view: View, position: Int, id: Long) {
        val checkable = view as Checkable
        checkable.toggle()
        adapter.setItemChecked(position, checkable.isChecked)
    }

    override fun onCreateLoader(id: Int, args: Bundle?): Loader<Cursor> {
        // filter for this item, but keep other lists
        val uri = Lists.buildListsWithListItemUri(
            ListItems.generateListItemIdWildcard(showTmdbId, ListItemTypes.TMDB_SHOW)
        )
        return CursorLoader(
            requireContext(), uri, ListsQuery.PROJECTION,
            null, null, Lists.SORT_ORDER_THEN_NAME
        )
    }

    override fun onLoadFinished(loader: Loader<Cursor>, data: Cursor) {
        adapter.swapCursor(data)
    }

    override fun onLoaderReset(loader: Loader<Cursor>) {
        adapter.swapCursor(null)
    }

    private class ListsAdapter(context: Context) : CursorAdapter(context, null, 0) {
        private val layoutInflater: LayoutInflater = LayoutInflater.from(context)
        val checkedPositions: SparseBooleanArray = SparseBooleanArray()

        override fun bindView(view: View, context: Context, cursor: Cursor) {
            val checkedView = view.findViewById<CheckedTextView>(android.R.id.text1)
            checkedView.text = cursor.getString(ListsQuery.NAME)

            val position = cursor.position

            // prefer state set by user over database
            val isChecked: Boolean
            if (checkedPositions.indexOfKey(position) >= 0) {
                // user has changed checked state, prefer it
                isChecked = checkedPositions[position]
            } else {
                // otherwise prefer database state, check if item is in this list
                val itemId = cursor.getString(ListsQuery.LIST_ITEM_ID)
                isChecked = !itemId.isNullOrEmpty()
                checkedPositions.put(position, isChecked)
            }
            checkedView.isChecked = isChecked
        }

        override fun newView(context: Context, cursor: Cursor, parent: ViewGroup): View {
            return layoutInflater.inflate(R.layout.item_list_checked, parent, false)
        }

        fun setItemChecked(position: Int, value: Boolean) {
            checkedPositions.put(position, value)
        }
    }

    interface ListsQuery {
        companion object {
            val PROJECTION = arrayOf(
                Tables.LISTS + "." + Lists._ID,
                Tables.LISTS + "." + Lists.LIST_ID,
                Lists.NAME,
                ListItems.LIST_ITEM_ID
            )
            const val LIST_ID = 1
            const val NAME = 2
            const val LIST_ITEM_ID = 3
        }
    }

    companion object {
        private const val TAG = "listsdialog"
        private const val ARG_LONG_SHOW_ID = "show_id"

        private fun newInstance(showId: Long): ManageListsDialogFragment {
            val f = ManageListsDialogFragment()
            val args = Bundle()
            args.putLong(ARG_LONG_SHOW_ID, showId)
            f.arguments = args
            return f
        }

        /**
         * Display a dialog which asks if the user wants to add the given show to one or more lists.
         */
        @JvmStatic
        fun show(fm: FragmentManager, showId: Long): Boolean {
            if (showId <= 0) return false
            // replace any currently showing list dialog (do not add it to the back stack)
            val ft = fm.beginTransaction()
            val prev = fm.findFragmentByTag(TAG)
            if (prev != null) {
                ft.remove(prev)
            }
            return newInstance(showId).safeShow(fm, ft, TAG)
        }
    }
}