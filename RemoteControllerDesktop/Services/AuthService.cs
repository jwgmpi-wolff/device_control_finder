using System.IO;
using System.Net.Http;
using System.Net.Http.Json;
using Google.Apis.Auth.OAuth2;
using Google.Apis.Util.Store;

namespace RemoteControllerDesktop.Services;

public sealed record LoginRequest(string Email, string Password);
public sealed record LoginResponse(string Token, string UserId);

public sealed class AuthService(HttpClient client)
{
    public async Task<LoginResponse> GoogleLoginAsync(CancellationToken cancellationToken = default)
    {
        var clientSecretsPath = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            "RemoteControllerDesktop", "google-oauth-client.json");
        if (!File.Exists(clientSecretsPath))
            throw new FileNotFoundException("Google OAuth desktop client JSON is not configured.", clientSecretsPath);

        var secrets = GoogleClientSecrets.FromFile(clientSecretsPath).Secrets;
        var credential = await GoogleWebAuthorizationBroker.AuthorizeAsync(
            secrets,
            ["openid", "email", "profile"],
            "remote-controller",
            cancellationToken,
            new FileDataStore(Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                "RemoteControllerDesktop", "GoogleAuth")));
        var idToken = credential.Token.IdToken;
        if (string.IsNullOrWhiteSpace(idToken))
            throw new InvalidOperationException("Google did not return an ID token.");
        using var response = await client.PostAsJsonAsync(
            "auth/google", new { idToken }, cancellationToken);
        response.EnsureSuccessStatusCode();
        return await response.Content.ReadFromJsonAsync<LoginResponse>(cancellationToken)
            ?? throw new InvalidOperationException("Server returned an empty Google login response.");
    }

    public async Task<LoginResponse> RegisterAsync(
        string email, string password, CancellationToken cancellationToken = default)
    {
        using var response = await client.PostAsJsonAsync(
            "auth/register", new LoginRequest(email, password), cancellationToken);
        response.EnsureSuccessStatusCode();
        return await response.Content.ReadFromJsonAsync<LoginResponse>(cancellationToken)
            ?? throw new InvalidOperationException("Server returned an empty registration response.");
    }

    public async Task<LoginResponse> LoginAsync(string email, string password, CancellationToken cancellationToken = default)
    {
        using var response = await client.PostAsJsonAsync(
            "auth/login", new LoginRequest(email, password), cancellationToken);
        response.EnsureSuccessStatusCode();
        return await response.Content.ReadFromJsonAsync<LoginResponse>(cancellationToken)
            ?? throw new InvalidOperationException("Server returned an empty login response.");
    }
}
