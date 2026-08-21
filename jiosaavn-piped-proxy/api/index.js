const express = require('express');
const fetch = require('node-fetch');
const app = ModernExpressApp();

function ModernExpressApp() {
  const expressApp = express();
  return expressApp;
}

const SAAVN_API = 'https://jiosaavn-api-beta.vercel.app';

// Helper: extract the first primary artist name as a string from various formats
function extractArtistName(artists) {
  if (!artists) return 'JioSaavn';
  if (typeof artists === 'string') return artists;
  if (Array.isArray(artists)) {
    const primary = artists.find(a => a.role === 'primary_artists') || artists[0];
    return primary ? (primary.name || 'JioSaavn') : 'JioSaavn';
  }
  return 'JioSaavn';
}

// Helper to convert JioSaavn song object to Piped StreamItem format
function toPipedStreamItem(song) {
  const thumbnail = song.image && song.image.length > 0 
    ? song.image[song.image.length - 1].link || song.image[song.image.length - 1].url 
    : '';

  const uploaderName = extractArtistName(song.artists?.primary) 
    || extractArtistName(song.artists?.all)
    || (typeof song.primaryArtists === 'string' ? song.primaryArtists : null)
    || 'JioSaavn';

  return {
    url: `/watch?v=${song.id}`,
    type: 'stream',
    title: song.name || 'Unknown Title',
    thumbnail: thumbnail,
    uploaderName: uploaderName,
    uploaderUrl: `/channel/${song.album?.id || 'unknown'}`,
    uploaderAvatar: null,
    uploadedDate: song.year || 'Unknown Date',
    shortDescription: null,
    duration: parseInt(song.duration) || 0,
    views: -1,
    uploaded: -1,
    uploaderVerified: false,
    isShort: false
  };
}

// Helper to convert JioSaavn album object to Piped Playlist/Album format
function toPipedPlaylistItem(album) {
  const thumbnail = album.image && album.image.length > 0 
    ? album.image[album.image.length - 1].link || album.image[album.image.length - 1].url 
    : '';

  // Extract artist name as a string — primaryArtists may be a string or array of objects
  const artistName = (typeof album.artist === 'string' && album.artist)
    ? album.artist
    : extractArtistName(album.primaryArtists);

  // Prefix with jsa_ so Android can distinguish JioSaavn IDs from local playlist IDs
  // Use 'name' (not 'title') — ContentItem uses 'name' for playlist items
  return {
    url: `/playlist?list=jsa_${album.id}`,
    type: 'playlist',
    name: artistName !== 'JioSaavn' ? (album.name || album.title || 'Unknown Album') : (album.name || album.title || 'Unknown Album'),
    title: album.name || album.title || 'Unknown Album',
    thumbnail: thumbnail,
    uploaderName: artistName,
    uploaderUrl: `/channel/${album.artistId || 'unknown'}`,
    uploaderAvatar: null,
    uploadedDate: album.year || 'Unknown Date',
    shortDescription: null,
    duration: -1,
    views: -1,
    uploaded: -1,
    uploaderVerified: false,
    isShort: false,
    videos: parseInt(album.songCount) || -1
  };
}

// Helper to convert JioSaavn artist object to Piped Channel format
function toPipedChannelItem(artist) {
  const thumbnail = artist.image && artist.image.length > 0 
    ? artist.image[artist.image.length - 1].link || artist.image[artist.image.length - 1].url 
    : '';

  return {
    url: `/channel/${artist.id}`,
    type: 'channel',
    name: artist.name || artist.title || 'Unknown Artist',
    thumbnail: thumbnail,
    uploaderName: null,
    uploaderUrl: null,
    uploaderAvatar: null,
    uploadedDate: null,
    shortDescription: artist.role || 'Artist',
    duration: -1,
    views: -1,
    uploaded: -1,
    uploaderVerified: false,
    isShort: false,
    subscribers: -1,
    videos: -1
  };
}

// 1. Search Endpoint (/search?q=query)
app.get('/search', async (req, res) => {
  const query = req.query.q || req.query.query || '';
  const filter = req.query.filter || 'all';
  if (!query) {
    return res.json({ items: [], nextPagepath: null });
  }

  try {
    let endpoint = '/search/songs';
    let mapper = toPipedStreamItem;

    if (filter === 'jiosaavn_albums' || filter === 'music_albums') {
      endpoint = '/search/albums';
      mapper = toPipedPlaylistItem;
    } else if (filter === 'jiosaavn_artists' || filter === 'music_artists') {
      endpoint = '/search/artists';
      mapper = toPipedChannelItem;
    } else if (filter === 'jiosaavn_playlists' || filter === 'music_playlists') {
      endpoint = '/search/playlists';
      mapper = toPipedPlaylistItem;
    }

    const response = await fetch(`${SAAVN_API}${endpoint}?query=${encodeURIComponent(query)}&limit=30`);
    const data = await response.json();

    const results = data.data?.results || data.data || [];
    if (results.length > 0) {
      const items = results.map(mapper);
      return res.json({ items: items, nextPagepath: null });
    }
    
    res.json({ items: [], nextPagepath: null });
  } catch (error) {
    console.error('Search error:', error);
    res.json({ items: [], nextPagepath: null });
  }
});

// 2. Stream Details Endpoint (/streams/videoId)
app.get('/streams/:videoId', async (req, res) => {
  const songId = req.params.videoId;
  if (!songId) {
    return res.status(400).json({ error: 'Missing videoId' });
  }

  try {
    const response = await fetch(`${SAAVN_API}/songs?id=${songId}`);
    const data = await response.json();

    const songs = data.data || [];
    if (songs.length > 0) {
      const song = songs[0];
      
      // Get highest quality audio link available
      const downloadUrls = song.downloadUrl || [];
      const highestQuality = downloadUrls.length > 0 
        ? downloadUrls[downloadUrls.length - 1].link || downloadUrls[downloadUrls.length - 1].url 
        : '';
        
      const thumbnail = song.image && song.image.length > 0 
        ? song.image[song.image.length - 1].link || song.image[song.image.length - 1].url 
        : '';

      const uploader = song.artists?.primary?.[0]?.name || song.artists?.all?.[0]?.name || song.primaryArtists || 'JioSaavn';

      // Format response exactly as Piped /streams endpoint expects
      const pipedResponse = {
        title: song.name,
        description: `Album: ${song.album?.name || 'Single'} | Year: ${song.year || ''}`,
        uploader: uploader,
        uploaderUrl: `/channel/${song.album?.id || 'unknown'}`,
        uploaderAvatar: null,
        thumbnailUrl: thumbnail,
        duration: parseInt(song.duration) || 0,
        audioStreams: [
          {
            url: highestQuality,
            format: 'M4A',
            quality: '320kbps',
            mimeType: 'audio/mp4',
            bitrate: 320000,
            codec: 'mp4a.40.2'
          }
        ],
        videoStreams: [],
        subtitles: [],
        relatedStreams: [],
        livestream: false
      };

      return res.json(pipedResponse);
    }
    res.status(404).json({ error: 'Song not found' });
  } catch (error) {
    console.error('Stream info error:', error);
    res.status(500).json({ error: error.message });
  }
});

// 3. Autocomplete Suggestions Endpoint (/suggestions?query=query)
app.get('/suggestions', async (req, res) => {
  const query = req.query.query || '';
  if (!query) {
    return res.json([]);
  }

  try {
    const response = await fetch(`${SAAVN_API}/search/songs?query=${encodeURIComponent(query)}&limit=7`);
    const data = await response.json();

    const results = data.data?.results || data.data || [];
    if (results.length > 0) {
      const suggestions = results.map(song => song.name);
      return res.json(suggestions);
    }
    res.json([]);
  } catch (error) {
    console.error('Suggestions error:', error);
    res.json([]);
  }
});

// Mock instance info endpoint to satisfy LibreTube startup checks
app.get('/instance', (req, res) => {
  res.json({
    name: 'JioSaavn Piped Proxy',
    version: '1.0.0',
    piped_self_hosted: true
  });
});

// 4. Playlists/Albums Endpoint (/playlists/:playlistId)
app.get('/playlists/:playlistId', async (req, res) => {
  let playlistId = req.params.playlistId;
  if (!playlistId) {
    return res.status(400).json({ error: 'Missing playlistId' });
  }

  // Strip the jsa_ prefix that the search results add to distinguish JioSaavn IDs
  if (playlistId.startsWith('jsa_')) {
    playlistId = playlistId.slice(4);
  }

  try {
    // Check if the playlistId is purely numeric. JioSaavn API uses numeric IDs for albums/playlists,
    // but the shared link might contain an alphanumeric token like TODtx4wo8yU_ which works via ?link=
    const isNumeric = /^\d+$/.test(playlistId);
    let fetchUrl = isNumeric 
      ? `${SAAVN_API}/albums?id=${playlistId}`
      : `${SAAVN_API}/albums?link=https://www.jiosaavn.com/album/kanchana/${playlistId}`;

    let response = await fetch(fetchUrl);
    let data = await response.json();

    let title = 'Unknown JioSaavn Playlist';
    let description = '';
    let thumbnail = '';
    let uploader = 'JioSaavn';
    let songs = [];

    const isSuccess = data.success || data.status === 'SUCCESS';
    if (isSuccess && data.data && data.data.name) {
      const album = data.data;
      title = album.name || album.title || title;
      description = `Album | Year: ${album.year || 'Unknown'} | Genre: ${album.playCount || ''}`;
      thumbnail = album.image && album.image.length > 0 
        ? album.image[album.image.length - 1].link || album.image[album.image.length - 1].url 
        : '';
      uploader = album.artist || album.primaryArtists || uploader;
      songs = album.songs || [];
    } else {
      // Try fetching as playlist
      fetchUrl = isNumeric
        ? `${SAAVN_API}/playlists?id=${playlistId}`
        : `${SAAVN_API}/playlists?link=https://www.jiosaavn.com/featured/playlist/${playlistId}`;
      response = await fetch(fetchUrl);
      data = await response.json();
      const isPlaylistSuccess = data.success || data.status === 'SUCCESS';
      if (isPlaylistSuccess && data.data && data.data.name) {
        const playlist = data.data;
        title = playlist.name || playlist.title || title;
        description = `Playlist | Fan Count: ${playlist.fanCount || ''}`;
        thumbnail = playlist.image && playlist.image.length > 0 
          ? playlist.image[playlist.image.length - 1].link || playlist.image[playlist.image.length - 1].url 
          : '';
        uploader = playlist.firstname || uploader;
        songs = playlist.songs || [];
      }
    }

    const relatedStreams = songs.map(toPipedStreamItem);

    const playlistResponse = {
      name: title,
      description: description,
      thumbnailUrl: thumbnail,
      bannerUrl: null,
      nextpage: null,
      uploader: uploader,
      uploaderUrl: `/channel/${playlistId}`,
      uploaderAvatar: null,
      videos: relatedStreams.length,
      relatedStreams: relatedStreams
    };

    return res.json(playlistResponse);
  } catch (error) {
    console.error('Playlist load error:', error);
    res.status(500).json({ error: error.message });
  }
});

module.exports = app;

if (require.main === module) {
  const PORT = process.env.PORT || 3000;
  app.listen(PORT, () => console.log(`Proxy running on http://localhost:${PORT}`));
}
