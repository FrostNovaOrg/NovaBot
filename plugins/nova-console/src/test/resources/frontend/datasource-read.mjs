// Shared responses for fixtures that exercise the push editor against an in-memory file.
export function datasourceRead(path, content) {
  try {
    const users = JSON.parse(content);
    if (!Array.isArray(users)) throw new Error('Expected an array');
    if (String(path).endsWith('/users')) return {success: true, users};
    const parts = String(path).split('/users/')[1]?.split('/');
    const user = users.find(user => user.platform === decodeURIComponent(parts?.[0])
      && String(user.uid) === decodeURIComponent(parts?.[1]));
    return user ? {success: true, user, version: 'v1'} : {success: false, message: 'Unknown streamer'};
  } catch (e) { return {success: false, message: e.message}; }
}
