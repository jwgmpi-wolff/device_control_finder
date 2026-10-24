using System.Net.Http;
using System.Net.Http.Json;
using System.Net.Http.Headers;

namespace RemoteControllerDesktop.Services;

public sealed record Device(string Id, string Name, string CreatedAt);
public sealed record DevicesResponse(IReadOnlyList<Device> Devices);
public sealed record RequestBody(string Capability);
public sealed record RequestResponse(string SessionId, string Status);

public sealed class CommandService(HttpClient client)
{
    public async Task<IReadOnlyList<Device>> GetDevicesAsync(string token, CancellationToken cancellationToken = default)
    {
        using var request = new HttpRequestMessage(HttpMethod.Get, "devices");
        request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", token);
        using var response = await client.SendAsync(request, cancellationToken);
        response.EnsureSuccessStatusCode();
        var result = await response.Content.ReadFromJsonAsync<DevicesResponse>(cancellationToken);
        return result?.Devices ?? throw new InvalidOperationException("Server returned an empty device list.");
    }

    public async Task<RequestResponse> RequestAsync(
        string token, string deviceId, string capability, CancellationToken cancellationToken = default)
    {
        using var request = new HttpRequestMessage(HttpMethod.Post, $"devices/{Uri.EscapeDataString(deviceId)}/requests")
        {
            Content = JsonContent.Create(new RequestBody(capability))
        };
        request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", token);
        using var response = await client.SendAsync(request, cancellationToken);
        response.EnsureSuccessStatusCode();
        return await response.Content.ReadFromJsonAsync<RequestResponse>(cancellationToken)
            ?? throw new InvalidOperationException("Server returned an empty request response.");
    }
}
