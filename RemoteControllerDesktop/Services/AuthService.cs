using System.Net.Http.Json;

namespace RemoteControllerDesktop.Services;

public sealed record LoginRequest(string Email, string Password);
public sealed record LoginResponse(string Token, string UserId);

public sealed class AuthService(HttpClient client)
{
    public async Task<LoginResponse> LoginAsync(string email, string password, CancellationToken cancellationToken = default)
    {
        using var response = await client.PostAsJsonAsync(
            "auth/login", new LoginRequest(email, password), cancellationToken);
        response.EnsureSuccessStatusCode();
        return await response.Content.ReadFromJsonAsync<LoginResponse>(cancellationToken)
            ?? throw new InvalidOperationException("Server returned an empty login response.");
    }
}
